package dev.fpga.rapidwright;

import com.google.gson.stream.JsonReader;
import com.xilinx.rapidwright.design.*;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.device.*;
import com.google.gson.*;
import com.xilinx.rapidwright.edif.*;
import com.xilinx.rapidwright.util.RapidWright;
import jnr.ffi.annotations.In;
import org.json.JSONObject;
import org.python.antlr.ast.Str;
import org.python.modules.math;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

public class json2dcp {

    static class NextpnrNet {

        public NextpnrNet(String name) {
            this.name = name;
            this.params = new HashMap<>();
            this.attrs = new HashMap<>();
            this.driver = null;
            this.users = new ArrayList<>();
            this.rwNet = null;
        }

        public String name;
        public HashMap<String, String> params;
        public HashMap<String, String> attrs;
        public NextpnrCellPort driver;
        public ArrayList<NextpnrCellPort> users;
        public Net rwNet;
    }

    enum PortDirection {
        PORT_IN,
        PORT_OUT,
        PORT_INOUT
    }

    public static PortDirection parsePortDir(String s) {
        if (s.equals("input"))
            return PortDirection.PORT_IN;
        else if (s.equals("output"))
            return PortDirection.PORT_OUT;
        else if (s.equals("inout"))
            return PortDirection.PORT_INOUT;
        else
            throw new RuntimeException("bad port direction " + s);
    }

    static class NextpnrCellPort {

        public NextpnrCellPort(NextpnrCell cell, String name, PortDirection type) {
            this.cell = cell;
            this.name = name;
            this.type = type;
            this.net = null;
        }

        public NextpnrCell cell;
        public String name;
        public PortDirection type;
        public NextpnrNet net;
    }

    static class NextpnrCell {

        public NextpnrCell(String name, String type) {
            this.name = name;
            this.type = type;
            this.ports = new HashMap<>();
            this.params = new HashMap<>();
            this.attrs = new HashMap<>();
            this.rwCell = null;
        }

        public String name, type;
        public HashMap<String, NextpnrCellPort> ports;
        public HashMap<String, String> params;
        public HashMap<String, String> attrs;
        public Cell rwCell;
    }

    static class NextpnrDesign {
        // json index --> net
        public HashMap<Integer, NextpnrNet> nets;
        // name --> cell
        public HashMap<String, NextpnrCell> cells;
        public Design rwd;

        public NextpnrDesign() {
            nets = new HashMap<>();
            cells = new HashMap<>();
            rwd = null;
        }

        String parseParam(JsonElement val) {
            JsonPrimitive prim = val.getAsJsonPrimitive();
            if (prim.isNumber()) {
                int p = prim.getAsInt();
                int size = 1;
                if (p < 0) {
                    size = 32;
                } else {
                    while (p >= (1L << size))
                        ++size;
                }
                return "32'h" + Integer.toHexString(p);
            } else {
                String s = prim.getAsString();
                boolean is_binary = true;
                for (char c : s.toCharArray()) {
                    if (c != '0' && c != '1' && c != 'x') {
                        is_binary = false;
                        break;
                    }
                }
                if (is_binary) {
                    s = s.replace('x', '0');
                    BigInteger bi = new BigInteger(s, 2);
                    return s.length() + "'h" + bi.toString(16);
                }
                return s;
            }
        }

        void Import(JsonObject des) {
            JsonObject top = des.getAsJsonObject("modules").getAsJsonObject(des.getAsJsonObject("modules").keySet().toArray()[0].toString());
            JsonObject netJson = top.getAsJsonObject("netnames");
            for(Map.Entry<String, JsonElement> entry : netJson.entrySet()) {
                NextpnrNet net = new NextpnrNet(entry.getKey());
                JsonObject data = entry.getValue().getAsJsonObject();
                int index = data.getAsJsonArray("bits").get(0).getAsInt();

                if (data.has("attributes")) {
                    JsonObject attrs = data.getAsJsonObject("attributes");
                    for (Map.Entry<String, JsonElement> attr : attrs.entrySet())
                        net.attrs.put(attr.getKey(), attr.getValue().getAsString());
                }

                nets.put(index, net);
            }

            JsonObject cellJson = top.getAsJsonObject("cells");
            for(Map.Entry<String, JsonElement> entry : cellJson.entrySet()) {
                JsonObject data = entry.getValue().getAsJsonObject();
                NextpnrCell cell = new NextpnrCell(entry.getKey(), data.get("type").getAsString());

                JsonObject pdirs = data.getAsJsonObject("port_directions");
                for (Map.Entry<String, JsonElement> pdir : pdirs.entrySet())
                    cell.ports.put(pdir.getKey(), new NextpnrCellPort(cell, pdir.getKey(), parsePortDir(pdir.getValue().getAsString())));

                JsonObject pconns = data.getAsJsonObject("connections");
                for (Map.Entry<String, JsonElement> pc : pconns.entrySet()) {
                    NextpnrCellPort port = cell.ports.get(pc.getKey());
                    JsonArray conn = pc.getValue().getAsJsonArray();
                    if (conn.size() > 0) {
                        NextpnrNet net = nets.get(conn.get(0).getAsInt());
                        port.net = net;
                        if (port.type == PortDirection.PORT_OUT) {
                            net.driver = port;
                        } else {
                            net.users.add(port);
                        }
                    }

                }

                JsonObject attrs = data.getAsJsonObject("attributes");
                for (Map.Entry<String, JsonElement> attr : attrs.entrySet())
                    cell.attrs.put(attr.getKey(), attr.getValue().getAsString());

                // FIXME: parse numerical params correctly
                JsonObject params = data.getAsJsonObject("parameters");
                for (Map.Entry<String, JsonElement> param : params.entrySet())
                    cell.params.put(param.getKey(), parseParam(param.getValue()));

                cells.put(entry.getKey(), cell);
            }
        }
    }

    public static String escape_name(String name) {
        return name.replace("\\", "__").replace("/", "_");
    }

    public static String fixup_init(String orig, int K) {
        // Vivado seems *very* fussy here
        String hex = orig.split("'h")[1];
        int bits = (1 << K);
        int digits = Math.max(bits / 4, 1);
        while (hex.length() < digits)
            hex = "0" + hex;
        return bits + "'h" + hex;
    }

    public static void connect_log_and_phys(Net net, Cell cell, String logical_pin) {
        // Similar to RapidWright's net.connect; but handles some special cases correctly
        if (cell.getType().equals("INBUF") || cell.getType().equals("IBUFCTRL"))
            net.connect(cell, logical_pin); // must use connect here
        else if (cell.getPhysicalPinMapping(logical_pin) == null || cell.getBEL().getPin(cell.getPhysicalPinMapping(logical_pin)).getConnectedSitePinName() == null || logical_pin.endsWith("]")) {
            // Create logical connection only
            EDIFPortInst epi;
            if (logical_pin.endsWith("]")) {
                int open_pos = logical_pin.lastIndexOf('[');
                String log_bus = logical_pin.substring(0, open_pos);
                int port_index = Integer.parseInt(logical_pin.substring(open_pos + 1, logical_pin.length() - 1));
                int bus_width = cell.getEDIFCellInst().getPort(log_bus).getWidth();
                epi = net.getLogicalNet().createPortInst(log_bus, (bus_width - 1) - port_index, cell.getEDIFCellInst());
                //System.out.println(net.getName() + " -L-> " + epi.getName());

            } else {
                epi = net.getLogicalNet().createPortInst(logical_pin, cell.getEDIFCellInst());
            }
            // If there is a physical pin connect it too
            if (cell.getPhysicalPinMapping(logical_pin) != null &&
                    cell.getBEL().getPin(cell.getPhysicalPinMapping(epi.getName())).getConnectedSitePinName() != null) {
                String pin = cell.getBEL().getPin(cell.getPhysicalPinMapping(epi.getName())).getConnectedSitePinName();
                net.addPin(new SitePinInst(epi.getDirection() == EDIFDirection.OUTPUT, pin, cell.getSiteInst()));
            }
        } else {
            net.connect(cell, logical_pin);
        }
    }

    public static void main(String[] args) throws FileNotFoundException {

        if (args.length < 3) {
            System.err.println("Usage: json2dcp <device> <design.json> <design.dcp>");
            System.err.println("   e.g json2dcp xczu2cg-sbva484-1-e top_routed.json top_routed.dcp");
            System.exit(1);
        }

        NextpnrDesign ndes = new NextpnrDesign();
        ndes.Import(new JsonParser().parse(new FileReader(args[1])).getAsJsonObject());

        Design des = new Design("top", args[0]);

        HashMap<String, String> siteToPin = new HashMap<>();
        for (PackagePin p : des.getDevice().getActivePackage().getPackagePinMap().values())
            if (p != null && p.getSite() != null)
                siteToPin.put(p.getSite().getName(), p.getName());

        for (NextpnrCell nc : ndes.cells.values()) {
            if (!nc.attrs.containsKey("X_ORIG_TYPE"))
                continue;
            if (nc.type.equals("IOB_INBUF") || nc.type.equals("IOB_OUTBUF")  || nc.type.equals("IOB_IBUFCTRL")) {
                nc.rwCell = des.createAndPlaceIOB(nc.name, nc.type.equals("IOB_OUTBUF") ? PinType.OUT : PinType.IN,
                        siteToPin.get(nc.attrs.get("NEXTPNR_BEL").split("/")[0]), nc.attrs.getOrDefault("IOSTANDARD", "LVCMOS33"));
            } else {
                Unisim unitype = Unisim.valueOf(nc.attrs.get("X_ORIG_TYPE"));
                nc.rwCell = des.createAndPlaceCell(nc.name, unitype, nc.attrs.get("NEXTPNR_BEL"));

                Map<String, String> map = nc.rwCell.getPinMappingsP2L();
                Object[] pins = map.keySet().toArray();

                if (unitype != Unisim.PS8) {
                    for (Object p : pins)
                        nc.rwCell.removePinMapping(p.toString());
                    for (NextpnrCellPort p : nc.ports.values()) {
                        if (!nc.attrs.containsKey("X_ORIG_PORT_" + p.name))
                            continue;
                        String[] orig_ports = nc.attrs.get("X_ORIG_PORT_" + p.name).split(" ");

                        for (String orig : orig_ports)
                            if (!orig.trim().isEmpty())
                                nc.rwCell.addPinMapping(p.name, orig.trim());
                    }

                }

                for (Map.Entry<String, String> param : nc.params.entrySet()) {
                    String value = param.getValue();
                    if (param.getKey().equals("INIT")) {
                        switch(unitype) {
                            case LUT1:
                                value = fixup_init(value, 1);
                                break;
                            case LUT2:
                                value = fixup_init(value, 2);
                                break;
                            case LUT3:
                                value = fixup_init(value, 3);
                                break;
                            case LUT4:
                                value = fixup_init(value, 4);
                                break;
                            case LUT5:
                                value = fixup_init(value, 5);
                                break;
                            case LUT6:
                                value = fixup_init(value, 6);
                                break;
                            case FDRE:
                            case FDSE:
                            case FDCE:
                            case FDPE:
                                value = "1'b" + value.substring(value.length() - 1);
                                break;
                        }
                    }
                    nc.rwCell.addProperty(param.getKey(), value);
                    //System.out.println(param.getKey() + " = " + param.getValue());
                }

                if (nc.rwCell.getType().equals("RAMD64E")) {
                    // FIXME: move to nextpnr
                    nc.rwCell.addProperty("RAM_ADDRESS_MASK", "2'b11");
                    nc.rwCell.addProperty("RAM_ADDRESS_SPACE", "2'b11");
                }
            }

        }

        EDIFCell top = des.getNetlist().getTopCell();
        EDIFNet edif_gnd = EDIFTools.getStaticNet(NetType.GND, top, des.getNetlist());
        EDIFNet edif_vcc = EDIFTools.getStaticNet(NetType.VCC, top, des.getNetlist());

        for (NextpnrNet nn : ndes.nets.values()) {
            //System.out.println("create net " + nn.name);
            Net n;
            if (nn.name.equals("$PACKER_VCC_NET")) {
                n = new Net("GLOBAL_LOGIC1", edif_vcc);
                des.addNet(n);
            }
            else if (nn.name.equals("$PACKER_GND_NET")) {
                n = new Net("GLOBAL_LOGIC0", edif_gnd);
                des.addNet(n);
            }
            else {
                n = new Net(escape_name(nn.name), new EDIFNet(escape_name(nn.name), des.getTopEDIFCell()));
                des.addNet(n);
            }
            nn.rwNet = n;
            if (nn.driver != null && nn.driver.cell.rwCell != null) {
                if (!nn.driver.cell.attrs.containsKey("X_ORIG_PORT_" + nn.driver.name))
                    continue;
                //System.out.println("connect " + n.getName() + " <- " + nn.driver.cell.name + "." + nn.driver.name);
                connect_log_and_phys(n, nn.driver.cell.rwCell, nn.driver.cell.attrs.get("X_ORIG_PORT_" + nn.driver.name));
            }
            for (NextpnrCellPort usr : nn.users) {
                if (usr.cell.rwCell != null) {
                    if (usr.cell.attrs.containsKey("X_ORIG_PORT_" + usr.name)) {
                        String[] orig_ports = usr.cell.attrs.get("X_ORIG_PORT_" + usr.name).split(" ");
                        for (String orig : orig_ports) {
                            connect_log_and_phys(n, usr.cell.rwCell, orig);
                            //n.connect(usr.cell.rwCell, orig);
                        }
                    } else {
                        // Special case where no logical pin exists, mostly where we tie A6 high for a fractured LUT
                        BELPin belPin = usr.cell.rwCell.getBEL().getPin(usr.name);
                        if (belPin != null && belPin.getConnectedSitePinName() != null) {
                            n.createPin(false, belPin.getConnectedSitePinName(), usr.cell.rwCell.getSiteInst());
                        }
                    }

                }
            }
        }

        for (NextpnrNet nn : ndes.nets.values()) {
            Net n = nn.rwNet;

            HashSet<String> inverted_wires = new HashSet<>();
            for (NextpnrCellPort sink : nn.users) {
                if (sink.cell.attrs.containsKey("X_ORIG_PORT_" + sink.name)) {
                    String[] orig_ports = sink.cell.attrs.get("X_ORIG_PORT_" + sink.name).split(" ");
                    if (sink.cell.rwCell == null)
                        continue;
                    for (String orig : orig_ports) {
                        if (!sink.cell.params.getOrDefault("IS_" + orig + "_INVERTED", "0").endsWith("1"))
                            continue;
                        BELPin sinkpin = sink.cell.rwCell.getBEL().getPin(sink.name);
                        if (sinkpin == null)
                            continue;
                        inverted_wires.add(sink.cell.rwCell.getSiteName() + "/" + sinkpin.getSiteWireName());
                        //System.out.println(sink.cell.rwCell.getSiteName() + "/" + sinkpin.getSiteWireName());
                    }
                }
            }

            String[] routing = nn.attrs.get("ROUTING").split(";");
            for (int i = 0; i < (routing.length-2); i+=3) {
                String wire = routing[i];
                String pip = routing[i+1];

                if (pip.isEmpty())
                    continue;
                if (!pip.startsWith("SITEPIP")) {
                    String[] sp = pip.split("/");
                    String tile = sp[0];
                    String[] wires = sp[1].split("\\.");
                    Tile t = des.getDevice().getTile(tile);
                    int src = Integer.parseInt(wires[0]), dst = Integer.parseInt(wires[1]);
                    if (src < t.getWireCount() && dst < t.getWireCount())
                        n.addPIP(t.getPIP(src, dst));
                }

            }

            for (int i = 0; i < (routing.length-2); i+=3) {
                String wire = routing[i];
                String pip = routing[i + 1];

                if (!pip.isEmpty() && pip.startsWith("SITEPIP") && !nn.name.equals("$PACKER_GND_NET")) {
                    String[] sp = pip.split("/");
                    SiteInst si = des.getSiteInstFromSiteName(sp[1]);
                    if (si == null)
                        si = des.createSiteInst(des.getDevice().getSite(sp[1]));
                    BEL b = si.getBEL(sp[2]);

                    if (b == null)
                        continue;

                    for (BELPin bp : b.getPins()) {
                        for (SitePIP sitePIP : bp.getSitePIPs()) {
                            if (sitePIP.getInputPin().getSiteWireName().equals(sp[3])) {

                                // Don't route through when inverting

                                if (inverted_wires.contains(si.getSiteName() + "/" + sitePIP.getOutputPin().getSiteWireName()))
                                    continue;

                                BELPin startPin = null;
                                for (BEL other : si.getBELs())
                                    for (BELPin p : other.getPins())
                                        if(p.isOutput() && p.getSiteWireName().equals(sitePIP.getInputPin().getSiteWireName()))
                                            startPin = p;
                                if (startPin != null) {
                                    for (BEL other : si.getBELs())
                                        for (BELPin p : other.getPins())
                                            if (p.isInput() && p.getSiteWireName().equals(sitePIP.getOutputPin().getSiteWireName()))
                                                si.routeIntraSiteNet(n, startPin, p);
                                }


                                si.addSitePIP(sitePIP);

                                // FIXME: when does/n't site PIP insertion work?

                            }
                        }
                    }

                }

                if (wire.startsWith("SITEWIRE") && !nn.name.equals("$PACKER_GND_NET")) {
                    String[] sw = wire.split("/");
                    SiteInst si = des.getSiteInstFromSiteName(sw[1]);
                    if (si == null)
                        si = des.createSiteInst(des.getDevice().getSite(sw[1]));
                    // FIXME: when does/n't site Wire insertion work?
                    BELPin startPin = null;
                    for (BEL other : si.getBELs()) {
                        if (other.getBELClass() == BELClass.RBEL || other.getBELClass() == BELClass.PORT)
                            continue;
                        for (BELPin p : other.getPins())
                            if(p.isOutput() && p.getSiteWireName().equals(sw[2]))
                                startPin = p;
                    }

                    if (startPin != null) {
                        for (BEL other : si.getBELs()) {
                            //if (other.getBELClass() == BELClass.RBEL || other.getBELClass() == BELClass.PORT)
                            //    continue;
                            for (BELPin p : other.getPins())
                                if (p.isInput() && p.getSiteWireName().equals(sw[2])) {
                                    si.routeIntraSiteNet(n, startPin, p);
                                    //System.out.println(startPin.getBEL().getName() + "." + startPin.getName() + " -> " + p.getBEL().getName() + "." + p.getName());
                                }
                        }

                    }
                }

            }
        }

        des.writeCheckpoint(args[2]);
    }

}
