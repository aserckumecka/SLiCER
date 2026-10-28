/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package slicer;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static java.nio.file.StandardCopyOption.COPY_ATTRIBUTES;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JOptionPane;
import parser.CefParser;
import parser.CsvParser;
import parser.JsnParser;
import parser.SyslogParser;

/**
 *
 * @author adrianoska
 */
public class Slicer implements Runnable {

    Lock lock = new ReentrantLock(true);
    // From SLiCER config file.
    int interval = 0; // Maximum time interval to generate a block.
    int size = 0; // Maximum size for a block.
    int buffer = 0; //Buffer size for processed events to minimize disk writing.
    String cache_path = new String(); // Path to cache folder.
    String cloud_path = new String(); // Path to cloud folder (linked to the clouds).
    String tmp_path = new String(); // Path to temporary folder (used by queries).
    String evtsource_path = new String(); // Sets the server socket as a receiver 
    //or the path to a stored dataset to feed SLiCER.

    String ip = new String(); // Address to start the server socket.
    int port = 9999; // Port to listen the incoming events.
    String compress = new String(); // Default compressing method.
    String indexer = new String(); // Default indexing method.
    String cost = new String(); // Estimated cost for each gigabyte downloaded from the cloud.

    HashMap<String, DevIndexer> hmapDev = new HashMap<>();
    List<String> devices = new ArrayList<>();
    // A buffer filled by server socket to enqueue the incoming events.
    List<String> eventBuffer = Collections.synchronizedList(new ArrayList<>());
    // List of existing devices and their attributes.
    //List<Device> devices = new ArrayList<>();
    boolean serverSocketIsAlive = false;
    CefParser cefp = null;
    Thread sender;
    Thread blockMonitor;
    boolean loadFinished = false;

    public Slicer() {
        cefp = new CefParser();
    }

    /**
     * Read the SLiCER config file and set the class variables.
     */
    public void slicerPathFileAnalyzer() throws IOException {
        List<String> confFile;
        confFile = Files.readAllLines(Paths.get("config/path.conf"));
        for (int i = 0; i < confFile.size(); i++) {
            String tmp = confFile.get(i);
            if (tmp.startsWith("cloud:")) {
                cloud_path = tmp.substring(tmp.indexOf(":") + 1);
                cloud_path = cloud_path.trim();
                if (!new File(cloud_path).exists()) {
                    Files.createDirectory(Paths.get(cloud_path));
                }
            } else if (tmp.startsWith("cache:")) {
                cache_path = tmp.substring(tmp.indexOf(":") + 1);
                cache_path = cache_path.trim();
                if (!new File(cache_path).exists()) {
                    Files.createDirectory(Paths.get(cache_path));
                }
            } else if (tmp.startsWith("tmp:")) {
                tmp_path = tmp.substring(tmp.indexOf(":") + 1);
                tmp_path = tmp_path.trim();
                if (!new File(tmp_path).exists()) {
                    Files.createDirectory(Paths.get(tmp_path));
                }
            }
        }
    }

    /**
     * Read the SLiCER config file and set the class variables.
     */
    public void slicerConfigFileAnalyzer() {

        List<String> confFile;
        try {
            confFile = Files.readAllLines(Paths.get("config/SLiCER.conf"));
            for (int i = 0; i < confFile.size(); i++) {
                if (confFile.get(i).startsWith("#")) {
                    confFile.remove(i);
                }
            }
            for (int i = 0; i < confFile.size(); i++) {
                String tmp = confFile.get(i);
                if (tmp.startsWith("interval")) {
                    interval = Integer.parseInt(tmp.substring(tmp.indexOf("=") + 1));
                } else if (tmp.startsWith("size")) {
                    size = Integer.parseInt(tmp.substring(tmp.indexOf("=") + 1));
                } else if (tmp.startsWith("buffer")) {
                    buffer = Integer.parseInt(tmp.substring(tmp.indexOf("=") + 1));
                } else if (tmp.startsWith("ip")) {
                    ip = tmp.substring(tmp.indexOf("=") + 1);
                    ip = ip.trim();
                } else if (tmp.startsWith("port")) {
                    port = Integer.parseInt(tmp.substring(tmp.indexOf("=") + 1));
                } else if (tmp.startsWith("evtsource")) {
                    evtsource_path = tmp.substring(tmp.indexOf("=") + 1);
                    evtsource_path = evtsource_path.trim();
                } else if (tmp.startsWith("compress")) {
                    compress = tmp.substring(tmp.indexOf("=") + 1);
                    compress = compress.trim();
                } else if (tmp.startsWith("indexer")) {
                    indexer = tmp.substring(tmp.indexOf("=") + 1);
                    indexer = indexer.trim();
                } else if (tmp.startsWith("cost")) {
                    cost = tmp.substring(tmp.indexOf("=") + 1);
                    cost = cost.trim();
                }
            }
            //readDefaultFormatConfigFile();
        } catch (IOException ex) {
            Logger.getLogger(Slicer.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    /**
     * Read all existing devices' config file and their params.
     */
    public void getDevicesConfigFile() {
        File file = new File("config/");
        File[] files = file.listFiles();

        if (files.length > 0) {
            for (int i = 0; i < files.length; i++) {
                if (files[i].isDirectory()) {
                    String[] identifier = new String[4];

                    try {
                        List<String> deviceConfig = Files.readAllLines(
                                Paths.get(files[i].toString() + "/device.conf"));
                        for (int d = 0; d < deviceConfig.size(); d++) {
                            String tmp = deviceConfig.get(d);
                            if (tmp.startsWith("dvchost=")) {
                                identifier[0] = tmp.substring(tmp.indexOf("=") + 1);
                            } else if (tmp.startsWith("dvc=")) {
                                identifier[1] = tmp.substring(tmp.indexOf("=") + 1);
                            } else if (tmp.startsWith("log_format=")) {
                                identifier[2] = tmp.substring(tmp.indexOf("=") + 1);
                            } else if (tmp.startsWith("timestamp=")) {
                                identifier[3] = tmp.substring(tmp.indexOf("=") + 1);
                            }
                        }
                        addDevToHMap(identifier);
                    } catch (IOException ex) {
                        Logger.getLogger(Slicer.class.getName()).log(Level.SEVERE, null, ex);
                    }
                }
            }
        }
    }

    public void addDevToHMap(String[] identifier) {
        String devName = new String();

        if (!identifier[0].isEmpty()) {
            devName = identifier[0];
        } else {
            devName = identifier[1];
        }

        createDevFolders(devName);
        DevIndexer di = new DevIndexer(
                devName,
                identifier[2],
                compress,
                identifier[3],
                cache_path,
                cloud_path,
                interval,
                size,
                buffer,
                indexer);
        hmapDev.put(devName, di);

        devices.add(devName);
    }

    public void createDevFolders(String devName) {
        try {
            if (!new File(cache_path + "/" + devName).exists()) {
                Files.createDirectory(Paths.get(cache_path + "/" + devName));
            }
            if (!new File(cache_path + "/" + devName + "/data").exists()) {
                Files.createDirectory(Paths.get(cache_path + "/" + devName + "/data"));
            }
            if (!new File(cache_path + "/" + devName + "/index").exists()) {
                Files.createDirectory(Paths.get(cache_path + "/" + devName + "/index"));
            }

            if (!new File(cloud_path + "/" + devName).exists()) {
                Files.createDirectory(Paths.get(cloud_path + "/" + devName));
            }
            if (!new File(cloud_path + "/" + devName + "/data").exists()) {
                Files.createDirectory(Paths.get(cloud_path + "/" + devName + "/data"));
            }
            if (!new File(cloud_path + "/" + devName + "/index").exists()) {
                Files.createDirectory(Paths.get(cloud_path + "/" + devName + "/index"));
            }
        } catch (IOException ex) {
            Logger.getLogger(Slicer.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    public void createNewDevice(String[] identifier, String devName) {

        try {
            lock.lock();

            File fileDev = new File("config/" + devName);
            if (!fileDev.exists()) {
                File file = new File("config/device.conf");
                Files.createDirectory(fileDev.toPath());
                Files.copy(Paths.get(file.toString()), Paths.get("config/" + devName + "/device.conf"), COPY_ATTRIBUTES);

                Path newDevConfigFile = Paths.get("config/" + devName + "/device.conf");
                List<String> deviceConfFile = Files.readAllLines(newDevConfigFile);
                String newDeviceConf = new String();

                for (int i = 0; i < deviceConfFile.size(); i++) {
                    String tmp = deviceConfFile.get(i);

                    if (tmp.startsWith("dvchost=")) {
                        deviceConfFile.set(i, "dvchost=" + identifier[0]);
                    } else if (tmp.startsWith("dvc=")) {
                        deviceConfFile.set(i, "dvc=" + identifier[1]);
                    } else if (tmp.startsWith("log_format=")) {
                        deviceConfFile.set(i, "log_format=" + identifier[2]);
                    } else if (tmp.startsWith("timestamp=")) {
                        deviceConfFile.set(i, "timestamp=" + identifier[3]);
                    }
                    newDeviceConf += deviceConfFile.get(i) + "\n";
                }
                byte[] peB = newDeviceConf.getBytes();
                Files.write(newDevConfigFile, peB, StandardOpenOption.CREATE);

                //Adding the new device to the hashmap...
                addDevToHMap(identifier);
            }
        } catch (IOException ex) {
            Logger.getLogger(Slicer.class.getName()).log(Level.SEVERE, null, ex);
        } finally {
            lock.unlock();
        }
    }

    // Event Listener... Server Socket
    @Override
    public void run() {
        //public void eventListener(){
        //SSocket ss = new SSocket();
        //EventSender es = new EventSender();
        //Thread t = new Thread(ss);
        //t.start();

        //String evt = new String(); // pegar o evento do socket
        ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() + 1);
        serverSocketIsAlive = true;

        // Start to monitoring devices in order to finish the blocks exceeding 
        // the predefined "interval" window.
        // This is verified everytime a new event has arrived, but if the 
        // events stop to came, the block stays open indefinitely.
        DevBlockMonitor devBlock = new DevBlockMonitor();
        blockMonitor = new Thread(devBlock);
        blockMonitor.start();

        Runnable thread;
        while (serverSocketIsAlive) {
            if (!eventBuffer.isEmpty()) {
                thread = new EvtProcess(eventBuffer.get(0));
                eventBuffer.remove(0);
                executor.execute(thread);
            }
                
        }
        executor.shutdown();

    }

//##############################################################################
//##############################################################################
//##############################################################################
    public class EvtProcess implements Runnable {

        String evt = new String();

        public EvtProcess(String evt) {
            this.evt = evt;
        }

        @Override
        public void run() {

            String[] fields = null;
            List<String> exten2Index = null;

            String devName = new String();
            String[] identifier = new String[4];
            identifier[0] = "";
            identifier[1] = "";
            if (evt.contains("CEF:")) {
                identifier[2] = "cef";
                identifier[3] = "rt"; // By default, CEF uses "rt" as timestamp field.

                fields = cefp.splitEvent(evt);
                exten2Index = cefp.splitExtenByField(fields[7]);

                if (evt.contains(" dvchost=")) {
                    int pos = evt.indexOf(" dvchost=");
                    String temp = evt.substring(pos + 1);
                    //temp = temp.trim();
                    int t1 = temp.indexOf("=") + 1;
                    int t2 = temp.indexOf(" ");
                    identifier[0] = temp.substring(t1, t2);
                }
                if (evt.contains(" dvc=")) {
                    int pos = evt.indexOf(" dvc=");
                    String temp = evt.substring(pos + 1);
                    identifier[1] = temp.substring(temp.indexOf("=") + 1, temp.indexOf(" "));
                } else {
                    identifier[0] = "notidentified";
                    identifier[1] = "notidentified";
                }
            } else if (evt.contains("Something to identify Syslog")) {
                identifier[0] = "";
                //...
            } else if (evt.contains("Something to identify CSV")) {
                identifier[0] = "";
                //...
            } else if (evt.contains("Something to identify JSON")) {
                identifier[0] = "";
                //...
            }

            if (!identifier[0].isEmpty()) {
                devName = identifier[0];
            } else {
                devName = identifier[1];
            }

            DevIndexer devIndexer = hmapDev.get(devName);
            if (devIndexer == null) {
                createNewDevice(identifier, devName);
                devIndexer = hmapDev.get(devName);
            }
            devIndexer.process(evt, fields, exten2Index);
        }
    }

//##############################################################################
//##############################################################################
//##############################################################################
    private class DevBlockMonitor implements Runnable {

        public DevBlockMonitor() {

        }

        @Override
        public void run() {
            try {
                //Thread.sleep(interval * 60 * 1000);
                while (serverSocketIsAlive) {
                    Thread.sleep(interval * 60 * 1000 / 20); // For each 1/10 of interval time.
                    hmapDev.forEach((k, v) -> {
                        DevIndexer di = v;
                        di.process("", null, null);

                        //if (di.intervalTimer != null && di.intervalTimer.plusMinutes(interval).compareTo(LocalDateTime.now()) <= 0
                        //        && di.eventsCount > 0) { // It works like a timer
                        //}
                    });
                    //Thread.sleep(interval / 10 * 1000); // For each 1/10 of interval time.
                }

            } catch (InterruptedException ex) {
                Logger.getLogger(Slicer.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
    }

//##############################################################################
//##############################################################################
//##############################################################################
    public class SSocket implements Runnable {

        //static ServerSocket variable
        private ServerSocket server;
        //socket server port on which it will listen
        //private int port = 9999;

        @Override
        public void run() {
            try {
                //create the socket server object
                server = new ServerSocket(port);
                //keep listens indefinitely until receives 'exit' call or program terminates
                while (true) {
                    //System.out.println("Waiting for client request");
                    //creating socket and waiting for client connection
                    Socket socket = server.accept();
                    //read from socket to ObjectInputStream object
                    ObjectInputStream ois = new ObjectInputStream(socket.getInputStream());

                    DataInputStream dis = new DataInputStream(socket.getInputStream());
                    //DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
                    //convert ObjectInputStream object to String
                    String event = (String) ois.readObject();
                    //System.out.println("Event Received: " + event);
                    eventBuffer.add(event);
                    //create ObjectOutputStream object
                    //ObjectOutputStream oos = new ObjectOutputStream(socket.getOutputStream());
                    //write object to Socket
                    //oos.writeObject(event);
                    //close resources
                    //ois.close();
                    //oos.close();
                    //socket.close();
                    //terminate the server if client sends exit request
                    //if (event.equalsIgnoreCase("exit")) {
                    //   serverSocketIsAlive = false;
                    //    break;
                    //}
                    // create a new thread object
                    //Thread t = new ClientHandler(socket, ois);

                    // Invoking the start() method
                    //t.start();
                    //System.out.println("Thread started: " + t);                    
                }
            } catch (IOException ex) {
                Logger.getLogger(Slicer.class.getName()).log(Level.SEVERE, null, ex);
            } catch (ClassNotFoundException ex) {
                Logger.getLogger(Slicer.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
    }

//##############################################################################
//##############################################################################
//##############################################################################
    class ClientHandler implements Runnable {

        final ObjectInputStream ois;
        final Socket s;

        // Constructor
        public ClientHandler(Socket s, ObjectInputStream ois) {
            this.s = s;
            this.ois = ois;
        }

        @Override
        public void run() {
            try {
                String received;

                while (true) {
                    received = (String) ois.readObject();
                    eventBuffer.add(received);
                    if (received.equals("exit")) {
                        try {
                            System.out.println("Client " + this.s + " sends exit...");
                            System.out.println("Closing this connection.");
                            this.s.close();
                            System.out.println("Connection closed");
                            break;
                        } catch (IOException ex) {
                            Logger.getLogger(Slicer.class.getName()).log(Level.SEVERE, null, ex);
                        }
                    }
                }
                // closing resources
                this.ois.close();
            } catch (IOException ex) {
                Logger.getLogger(Slicer.class.getName()).log(Level.SEVERE, null, ex);
            } catch (ClassNotFoundException ex) {
                Logger.getLogger(Slicer.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
    }

//##############################################################################
//##############################################################################
//##############################################################################
    public void sender(boolean start) {
        if (start) {
            File file = new File(evtsource_path);
            EventSender es = new EventSender(file);
            sender = new Thread(es);
            sender.start();
        } else {
            sender.interrupt();
        }

    }

    public class EventSender implements Runnable {

        File path = null;

        public EventSender(File path) {
            this.path = path;
        }

        @Override
        public void run() {
            try {
            File[] files = path.listFiles();
            if (files.length==0){
                JOptionPane.showMessageDialog(null, 
                        "Verify the path of the event's source folder (evtsource field) in Slicer.conf.\nIt has no files. Fix it and restart the system", 
                        "There is no events to feed SLiCER", 
                        JOptionPane.ERROR_MESSAGE);
            }
            
            Arrays.sort(files, (f1, f2) -> f1.compareTo(f2));
            List<String> confFile = new ArrayList<>();
            String filePath = new String();
            int count = 0;
            
                for (int i = 0; i < files.length; i++) {
                    if (files[i].isHidden()) {
                        continue;
                    }

                    System.out.println(files[i]);

                    filePath = files[i].toString();
                    confFile = Files.readAllLines(Paths.get(filePath));
                    for (int j = 0; j < confFile.size(); j++) {
                        eventBuffer.add(confFile.get(j));
                    }
                    //Thread.sleep(eventBuffer.size()/2000);
                }
                //Thread.sleep(eventBuffer.size()/2000);
                //loadFinished = true;
            } catch (Exception ex) {
                Logger.getLogger(Slicer.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
    }

//##############################################################################
    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        try {
            Slicer slicer = new Slicer();
            slicer.slicerPathFileAnalyzer();
            slicer.slicerConfigFileAnalyzer();
            slicer.getDevicesConfigFile();
            //slicer.createDevListeners();
            slicer.sender(true);
            //slicer.eventListener();
            Thread thread = new Thread(slicer);
            thread.start();
        } catch (IOException ex) {
            Logger.getLogger(Slicer.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
}
