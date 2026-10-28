/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package parser;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Scanner;
import java.util.logging.Level;
import java.util.logging.Logger;
import slicer.Slicer;

/**
 *
 * @author adrianoska
 */
public class CefParser {

    // CEF Config File
    public List<String> header = new ArrayList<>();
    public List<String> allExten = new ArrayList<>();
    public List<String> exten2Index = new ArrayList<>();
    String path = "config/cef.conf";
    String tsField = new String();
    public boolean rt = true;
    public boolean dtz = true;
    public boolean eventId = true;

    class Exten {

        int initpos = 0; // Initial position
        int endpos = 0; // End position
        String name = new String(); // field name
        String value = new String(); // field value
    }

    public CefParser() {
        readCefConfigFile();
    }

    private void readCefConfigFile() {
        List<String> cefConfig = new ArrayList<>();

        try {
            //cefConfig = Files.readAllLines(Paths.get(filePath + "/cef.conf"));
            Scanner scanner = new Scanner(new File(path));

            while (scanner.hasNext()) {
                String line = scanner.nextLine();
                if (!(line.isEmpty() || line.startsWith("###") || line.startsWith("#**"))) {
                    cefConfig.add(line);
                }
            }
            getParams(cefConfig);
        } catch (IOException ex) {
            Logger.getLogger(Slicer.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    public synchronized void getParams(List<String> cefConfig) {
        for (int i = 0; i < cefConfig.size(); i++) {
            String tmp = cefConfig.get(i);
            if (tmp.startsWith("HEADER")) {
                for (int h = i + 1; h < cefConfig.size(); h++) {
                    tmp = cefConfig.get(h);
                    tmp = tmp.trim();
                    if (!tmp.equals("")) {
                        if (tmp.startsWith("#version") || tmp.startsWith("version")) {
                            header.add(tmp.substring(0, tmp.indexOf(" ")));
                        } else if (tmp.startsWith("#devvendor") || tmp.startsWith("devvendor")) {
                            header.add(tmp.substring(0, tmp.indexOf(" ")));
                        } else if (tmp.startsWith("#devproduct") || tmp.startsWith("devproduct")) {
                            header.add(tmp.substring(0, tmp.indexOf(" ")));
                        } else if (tmp.startsWith("#devversion") || tmp.startsWith("devversion")) {
                            header.add(tmp.substring(0, tmp.indexOf(" ")));
                        } else if (tmp.startsWith("#deveventclid") || tmp.startsWith("deveventclid")) {
                            header.add(tmp.substring(0, tmp.indexOf(" ")));
                        } else if (tmp.startsWith("#eventname") || tmp.startsWith("eventname")) {
                            header.add(tmp.substring(0, tmp.indexOf(" ")));
                        } else if (tmp.startsWith("#severity") || tmp.startsWith("severity")) {
                            header.add(tmp.substring(0, tmp.indexOf(" ")));
                            i++;
                            break;
                        }
                    }
                    i = h;
                }
            } else if (tmp.startsWith("EXTENSIONS")) {
                for (int e = i + 1; e < cefConfig.size(); e++) {
                    String temp = cefConfig.get(e);
                    
                    if (temp.contains(" ")) {
                        temp = temp.substring(0, temp.indexOf(" ")); // if exists a full field after the reduced.                        
                    }
                    if (temp.startsWith("#")) {
                        allExten.add(temp.substring(1));
                    } else{
                        allExten.add(temp);
                        exten2Index.add(temp);
                    }
                    if (temp.startsWith("#rt")) {
                        exten2Index.add(temp.substring(1));
                        rt = false;
                    }
                    if (temp.startsWith("#dtz")) {
                        exten2Index.add(temp.substring(1));
                        dtz = false;
                    }
                    if (temp.startsWith("#eventId")) {
                        exten2Index.add(temp.substring(1));
                        eventId = false;
                    }
                }
                break;
            }
        }
    }

    /*
    It receives a String "event" containing a full event and split it into fields by "|" (pipe).
    A String[] is returned with the CEF fields, being the last of them the "extensions".
     */
    public synchronized String[] splitEvent(String event) {
        try{
        String[] fields = null;
        event = event.replaceAll("\\Q\\|\\E", "/"); //replacing \| by /
        fields = event.split("\\|");
        String temp = fields[0].toLowerCase();
        if (!(temp.startsWith("cef:") || temp.startsWith(" cef:"))) {
            String[] f = fields[0].split(" ");
            String tmp = "";
            for (int i = 0; i < f.length - 2; i++) {
                tmp = tmp + " " + f[i];
            }
            tmp = tmp + "@" + f[f.length - 2] + "@" + f[f.length - 1];
            fields[0] = tmp;
        }

        for (int i = 0; i < 7; i++) {
            fields[i] = header.get(i) + "=" + fields[i];
        }
        return fields;
        }catch(Exception ex){
            System.out.println(ex.getMessage());
            return null;
        }
    }

    /*
    It receives a String containing the "extensions" field in line, obtained from the 
    original CEF event. A String[] with the splitted subfields is returned.
     */
    public synchronized List<String> splitExtenBySpace(String ext) {
        ext = ext.trim();
        String[] extF = ext.split(" ");
        List<String> extF2 = new ArrayList<>();
        int ind = 0;
        String content = "";

        for (int i = 0; i < extF.length; i++) {
            //if the term starts with "=" or not contains "=" after position 1.
            if (!extF[i].substring(1).contains("=")) {
                if (ind > 0) {
                    extF[ind - 1] += " " + extF[i];
                    extF[i] = "";
                } else {
                    content = extF[i];
                    extF[i] = "";
                    extF[ind] += content;
                }
                //System.out.println(extF[i] + "\n");
            } else {
                content = extF[i];
                extF[i] = "";
                extF[ind] += content;
                //System.out.println(extF[ind] + "\n");
                //System.out.println("i=" + i + ", ind=" + ind);
                ind++;
            }
        }

        // Verify if the position of extensions array contains something and add it to the List.
        for (int i = 0; i < extF.length; i++) {
            if (extF[i] != "") {
                extF2.add(extF[i]);
            } else {
                break;
            }
        }

        for (int i = 0; i < extF2.size(); i++) {
            for (int j = 0; j < exten2Index.size(); j++) {
                String temp = " " + extF2.get(i);
                if (temp.contains(" " + exten2Index.get(j) + "=")) {
                    extF2.set(i, exten2Index.get(j) + "="
                            + extF2.get(i).substring(extF2.get(i).indexOf("=") + 1));
                    break;
                }
                if (j == exten2Index.size() - 1) {
                    extF2.remove(i);
                    i--;
                }
            }
        }

        return extF2;
    }

    /**
     * Splits the extension fields by the fields listed in the device config
     * file.
     *
     * @param ext The extensions field, or the last big field of a CEF.
     * @return The List<String> containing only those fields selected in the
     * cef.conf
     */
    public synchronized List<String> splitExtenByField(String ext) {
        List<Exten> exten = new ArrayList<>();

        // Assign the position of all possible fields. Those not existing in the 
        // event are assigned with -1.
        for (int i = 0; i < allExten.size(); i++) {
            Exten ex = new Exten();
            ex.initpos = ext.indexOf(" " + allExten.get(i) + "=");
            ex.name = allExten.get(i);
            if (ex.initpos != -1) { // Only inserts an extension (from allFields) if it is in the event.
                exten.add(ex);
            }
        }

        // Order the exten[] by initpos, in order to define endpos. (BubleSort)
        Collections.sort(exten, (Exten e1, Exten e2) -> e1.initpos - e2.initpos);

        // Assigning the ending position for every field.
        for (int i = 0; i < exten.size() - 1; i++) {
            exten.get(i).endpos = exten.get(i + 1).initpos;
        }
        exten.get(exten.size() - 1).endpos = ext.length(); // Assigning the last extension.

        for (int i = 0; i < exten.size(); i++) {
            for (int j = 0; j < exten2Index.size(); j++) {
                String temp = " " + exten.get(i).name + "=";
                if (temp.contains(" " + exten2Index.get(j) + "=")) {
                    temp = ext.substring(exten.get(i).initpos, exten.get(i).endpos);
                    exten.get(i).value = temp.substring(temp.indexOf("=") + 1);
                    break;
                }
                if (j == exten2Index.size() - 1) {
                    exten.remove(i);
                    i--;
                }
            }
        }

        List<String> f2i = new ArrayList<>(); // fields to index

        for (int i = 0; i < exten.size(); i++) {
            f2i.add(exten.get(i).name + "=" + exten.get(i).value);
        }
        return f2i;
    }

    public static void main(String args[]) {
//        CefParser cf = new CefParser("");
//        String evt = "CEF:0|Aruba Networks|ClearPass|7.5.0.7983.0|agent:050|Connector "
//                + "Raw Event Statistics|Low| eventId=294749 mrt=1496909658338 "
//                + "categorySignificance=/Informational categoryBehavior=/Execute/Response "
//                + "categoryDeviceGroup=/Application catdt=Security Management "
//                + "categoryOutcome=/Success categoryObject=/Host/Application "
//                + "art=1496909658353 cat=/Agent/RawEvent/Statistics deviceSeverity=Warning "
//                + "rt=1496909658338 fileType=Agent cs1=0.12766346641054535 "
//                + "cs2=37.47144537128769 cs3=0.39 cs4=34410 cs5=114.7 cs6=36JvphVsBABCQ6D+Npmyndw== "
//                + "cn1=270093 cn2=79276988 cn3=117 deviceCustomDate1=1496909358326 "
//                + "cs1Label=Event throughput cs2Label=Raw event character throughput "
//                + "cs3Label=Event throughput (SLC) cs4Label=Raw event length (SLC) "
//                + "cs5Label=Raw event character throughput (SLC) cs6Label=Destination ID "
//                + "cn1Label=Total event count cn2Label=Total raw event length "
//                + "cn3Label=Event count (SLC) deviceCustomDate1Label=Last time "
//                + "ahost=socxxx000.ABC.pt agt=10.20.4.3 agentZoneURI=/All Zones/ArcSight "
//                + "System/Private Address Space Zones/RFC1918: 10.0.0.0-10.255.255.255 "
//                + "amac=aa-A8-F0-32-95-aa av=7.5.0.7983.0 atz=Europe/Paris at=syslog "
//                + "dvchost=socxxx000.ABC.pt dvc=10.20.4.3 deviceZoneURI=/All Zones/ArcSight "
//                + "System/Private Address Space Zones/RFC1918: 10.0.0.0-10.255.255.255 "
//                + "dvcmac=40-A8-F0-32-95-98 dtz=Europe/Paris _cefVer=0.1 aid=36JvphVsBABCQ6D+Npmyndw==";
//        String allf = "#act\n"
//                + "app\n" + "#c6a1\n" + "#c6a1Label\n" + "#c6a2\n" + "#c6a2Label\n" + "#c6a3\n" + "#c6a3Label\n" + "#c6a4\n" + "#c6a4Label\n" + "#cfp1\n" + "#cfp1Label\n" + "#cfp2\n"
//                + "#cfp2Label\n" + "#cfp3\n" + "#cfp3Label\n" + "#cfp4\n" + "#cfp4Label\n" + "#cn1\n" + "#cn1Label\n" + "#cn2\n" + "#cn2Label\n" + "#cn3\n" + "#cn3Label\n" + "#cnt\n"
//                + "#cs1\n" + "#cs1Label\n" + "#cs2\n" + "#cs2Label\n" + "#cs3\n" + "#cs3Label\n" + "#cs4\n" + "#cs4Label\n" + "#cs5\n" + "#cs5Label\n" + "#cs6\n" + "#cs6Label\n"
//                + "#destinationDnsDomain\n" + "#destinationServiceName\n" + "#destinationTranslatedAddress\n" + "#destinationTranslatedPort\n" + "#deviceCustomDate1\n"
//                + "#deviceCustomDate1Label\n" + "#deviceCustomDate2\n" + "#deviceCustomDate2Label\n" + "#deviceDirection\n" + "#deviceDnsDomain\n" + "#deviceExternalId\n"
//                + "#deviceFacility\n" + "#deviceInboundInterface\n" + "#deviceNtDomain\n" + "#deviceOutboundInterface\n" + "#devicePayloadId\n" + "#deviceProcessName\n"
//                + "#deviceTranslatedAddress\n" + "dhost\n" + "dmac\n" + "#dntdom\n" + "#dpid\n" + "#dpriv\n" + "#dproc\n" + "#dpt\n" + "#dst\n" + "#dtz\n" + "#duid\n" + "duser\n"
//                + "dvc\n" + "#dvchost\n" + "dvcmac\n" + "#dvcpid\n" + "#end\n" + "#externalId\n" + "#fileCreateTime\n" + "#fileHash\n" + "#fileId\n" + "#fileModificationTime\n"
//                + "#filePath\n" + "#filePermission\n" + "#fileType\n" + "#flexDate1\n" + "#flexDate1Label\n" + "#flexNumber1\n" + "#flexNumber1Label\n" + "#flexNumber2\n"
//                + "#flexNumber2Label\n" + "#flexString1\n" + "#flexString1Label\n" + "#flexString2\n" + "#flexString2Label\n" + "#fname\n" + "#fsize\n" + "#in\n" + "#msg\n"
//                + "#oldFileCreateTime\n" + "#oldFileHash\n" + "#oldFileId\n" + "#oldFileModificationTime\n" + "#oldFileName\n" + "#oldFilePath\n" + "#oldFilePermission\n"
//                + "#oldFileSize\n" + "#oldFileType\n" + "#out\n" + "#outcome\n" + "#proto\n" + "#reason\n" + "#request\n" + "#requestClientApplication\n" + "#requestContext\n"
//                + "#requestCookies\n" + "#requestMethod\n" + "#rt\n" + "#shost\n" + "#smac\n" + "#sntdom\n" + "#sourceDnsDomain\n" + "#sourceServiceName\n" + "#sourceTranslatedAddress\n"
//                + "#sourceTranslatedPort\n" + "#spid\n" + "#spriv\n" + "#sproc\n" + "#spt\n" + "src\n" + "#start\n" + "#suid\n" + "suser\n" + "#type\n" + "#agentDnsDomain\n"
//                + "#agentNtDomain\n" + "#agentTranslatedAddress\n" + "#agentTranslatedZoneExternalID\n" + "#agentTranslatedZoneURI\n" + "#agentZoneExternalID\n" + "#agentZoneURI\n"
//                + "agt\n" + "ahost\n" + "aid\n" + "amac\n" + "#art\n" + "#at\n" + "#atz\n" + "#av\n" + "#cat\n" + "#customerExternalID\n" + "#customerURI\n"
//                + "#destinationTranslatedZoneExternalID\n" + "#destinationTranslatedZoneURI\n" + "#destinationZoneExternalID\n" + "#destinationZoneURI\n"
//                + "#deviceTranslatedZoneExternalID\n" + "#deviceTranslatedZoneURI\n" + "#deviceZoneExternalID\n" + "#deviceZoneURI\n" + "#dlat\n" + "#dlong\n" + "eventId\n"
//                + "#rawEvent\n" + "#slat\n" + "#slong\n" + "#sourceTranslatedZoneExternalID\n" + "#sourceTranslatedZoneURI\n" + "#sourceZoneExternalID\n" + "#sourceZoneURI\n" + "ad.message";
//
//        List<String> fields2Index = new ArrayList<>();
//        List<String> allFields = new ArrayList<>();
//        String[] temp = allf.split("\n");
//        for (int i = 0; i < temp.length; i++) {
//            if (!temp[i].startsWith("#")) {
//                fields2Index.add(temp[i]);
//            } else {
//                temp[i] = temp[i].substring(1);
//            }
//            allFields.add(temp[i]);
//        }
//        String[] fields = cf.splitEvent(evt);
//        List<String> exten = cf.splitExtenByField(fields[7]);
//        //List<String> exten = cf.splitExtenBySpace(fields[fields.length - 1], fields2Index, allFields);
//
//        for (int i = 0; i < exten.size(); i++) {
//            System.out.println(exten.get(i));
//        }
    }
}
