/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author adrianoska
 */
public class CefGenerator {

    String version = "CEF:0";
    String devvendor = new String();
    String devproduct = new String();
    String devversion = new String();
    String deveventclid = new String();
    String eventname = new String();
    String severity = new String();

    List<String> fieldsToIndex = new ArrayList<>();
    List<String> fieldsToRand = new ArrayList<>();

    public CefGenerator() {

    }

    public void read() throws IOException {
        try {
            Scanner scanner = new Scanner(new File("CEFgen/cefFields.txt"));
            while (scanner.hasNext()) {
                String line = scanner.nextLine();
                if (line.startsWith("#")) {
                    fieldsToRand.add(line.substring(1));
                } else {
                    fieldsToIndex.add(line);
                }
            }                        
            for (int i = 0; i < fieldsToIndex.size(); i++) {
                System.out.println(fieldsToIndex.get(i));
            }
                System.out.println("\n");
            for (int i = 0; i < fieldsToRand.size(); i++) {
                System.out.println(fieldsToRand.get(i));
            }
//            byte[] peb = line.getBytes();
//            Files.write(Paths.get("cefFields.txt"), peb, StandardOpenOption.CREATE);
        } catch (FileNotFoundException ex) {
            Logger.getLogger(CefGenerator.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    public static void main(String[] args) {
        try {
            CefGenerator cg = new CefGenerator();
            cg.read();
        } catch (IOException ex) {
            Logger.getLogger(CefGenerator.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
}
