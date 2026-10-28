/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package utils;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author aserckumecka
 */
public class Zpaq {
    
    public static void zpaqCompress(String dst, String src){
        try {
            Runtime rt = Runtime.getRuntime();
            Process prc = rt.exec("zpaq c " + dst + " " + src);
        } catch (IOException ex) {
            Logger.getLogger(Zpaq.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    public static void zpaqUncompress(String src, String dst){
        try {
            Runtime rt = Runtime.getRuntime();
            Process prc = rt.exec("zpaq x " + src + " " + dst);
        } catch (IOException ex) {
            Logger.getLogger(Zpaq.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    public static void main(String args[]){
        Zpaq.zpaqCompress(
                    "/home/adrianoska/Desktop/DC.edpcg.local_2017-11-17T23:22:12_2017-11-17T23:40:31.zpaq",
                    "/home/adrianoska/Desktop/DC.edpcg.local_2017-11-17T23:22:12_2017-11-17T23:40:31/" 
            );
        Zpaq.zpaqUncompress("/home/adrianoska/Desktop/DC.edpcg.local_2017-11-17T23:22:12_2017-11-17T23:40:31.zpaq",
                "/home/adrianoska/Desktop/teste"
            );
    }
}
