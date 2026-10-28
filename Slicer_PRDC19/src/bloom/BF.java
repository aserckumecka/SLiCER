package bloom;

//import coc.CefGen;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class BF {

    public static double falsePositiveRate = 0.0001;

    public BF() {

    }

    public static LongFastBloomFilter createBloom(long expectedNumberOfElements, double falsePosProb) {
        LongFastBloomFilter longFastBloomFilter = LongFastBloomFilter.getFilter(expectedNumberOfElements, falsePosProb);
        return longFastBloomFilter;
    }

    public static void storeBloom(LongFastBloomFilter lfb, Path pathFolder, String pathName) {
        DataOutputStream dos = null;
        try {
            if (!new File(pathFolder.toString()).exists()) {
                Files.createDirectory(pathFolder);
            }

            dos = new DataOutputStream(new FileOutputStream(pathFolder + "/" + pathName));
            LongFastBloomFilterSerializer lfsb = new LongFastBloomFilterSerializer();
            lfsb.serialize(lfb, dos);
        } catch (Exception ex) {
            Logger.getLogger(BF.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    public static LongFastBloomFilter retrieveBloom(File path) {
        DataInputStream dis = null;

        try {
            dis = new DataInputStream(new FileInputStream(path));
            LongFastBloomFilterSerializer lfbs = new LongFastBloomFilterSerializer();
            LongFastBloomFilter lfb = lfbs.deserialize(dis);
            return lfb;
        } catch (Exception ex) {
            Logger.getLogger(BF.class.getName()).log(Level.SEVERE, null, ex);
            return null;
        }
    }

    //It receives a folder path, reads the events and returns a list of File
    public static synchronized List<File> eventList(String path) {
        try {
            List<File> filesInFolder = Files.walk(Paths.get(path))
                    .filter(Files::isRegularFile)
                    .map(Path::toFile)
                    .collect(Collectors.toList());

            String[] files = new String[filesInFolder.size()];

            //List<String> list = Collections.sort(filesInFolder);;
            for (int i = 0; i < filesInFolder.size(); i++) {
                if (filesInFolder.get(i).isHidden()) {
                    filesInFolder.get(i).delete();
                }
            }

            for (int i = 0; i < filesInFolder.size(); i++) {
                files[i] = filesInFolder.get(i).toString();
            }

            Arrays.sort(files);
            filesInFolder.clear();

            for (int i = 0; i < files.length; i++) {
                File file = new File(files[i]);
                filesInFolder.add(file);
            }

            return filesInFolder;
        } catch (IOException ex) {
            Logger.getLogger(BF.class.getName()).log(Level.SEVERE, null, ex);
            return null;
        }
    }

    public static void feedBloom(Path indexPath, List<String[]> fieldsExten2Index) {
        // define and initialyze bloomFieldsFilled with the number of total fields.
        List<LongFastBloomFilter> lfb = new ArrayList<>();

        // store the names of parent path and the file name of fields to store later the bloom filters.
        List<String> bloomFieldNamePath = new ArrayList<>(); //file name
        
        //System.out.println("Index path: "+indexPath);
        for (int x = 0; x < fieldsExten2Index.size(); x++) {
            String[] words = fieldsExten2Index.get(x)[1].split(";");
            lfb.add(createBloom(words.length, falsePositiveRate)); // Initializing each bloom.
            bloomFieldNamePath.add(fieldsExten2Index.get(x)[0] + ".bf"); // set the bloom name
            //System.out.println(fieldsExten2Index.get(x)[0] +" = "+ fieldsExten2Index.get(x)[1]);
            // add all entries to its specific LongFastBloomFilter.
            for (int y = 0; y < words.length; y++) {
                lfb.get(lfb.size() - 1).add(words[y].getBytes());
            }
        }

        for (int z = 0; z < lfb.size(); z++) {
            storeBloom(lfb.get(z), indexPath, bloomFieldNamePath.get(z));
            //System.out.println(indexPath + bloomFieldNamePath.get(z));
        }
    }
    
    public boolean verifyTerm(File path, String termValue){
        LongFastBloomFilter lfb = BF.retrieveBloom(path);
        if (lfb.contains(termValue.getBytes())) {
            //System.out.println("true");
            return true;
        } else {
            //System.out.println("false");
            return false;
        }
    }

    public static void main(String[] args) {
        //long expectedNumberOfElements = 108000;
        //double falsePosProb = 0.01;
        //LongFastBloomFilter lfbf = BF.createBloom(expectedNumberOfElements, falsePosProb);
        //lfbf.add("x".toString().getBytes());

//        String path = "/home/adriano/Dropbox/Slicer/storage/tmp/index/DC.edpcg.local_2017-11-18T09:07:27_2017-11-18T09:17:34__bf/ahost.bf";
//        LongFastBloomFilter lfb = BF.retrieveBloom(new File(path));
//        String search = "COLECT.edpcg.local";
//        if (lfb.contains(search.getBytes())) {
//            System.out.println(search + " probably is in the set.");
//        } else {
//            System.out.println(search + " certanly is not in the set.");
//        }
        //BF bf = new BF();
        //bf.feedBloomBlock("/home/adrianoska/SLiCER/CEFgen", "/home/adrianoska/SLiCER/bloomFields.txt");
        BF bloom = new BF();
        boolean test = bloom.verifyTerm(new File("storage/tmp/index/DC.edpcg.local_2017-11-18T07:46:33_2017-11-18T07:56:40__bf/ahost.bf"), 
                "colect.edpcg.local");
        System.out.println(test);
    }
}
