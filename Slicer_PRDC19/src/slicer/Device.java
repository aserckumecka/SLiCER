/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package slicer;

import parser.CefParser;
import parser.CsvParser;
import parser.JsnParser;
import parser.SyslogParser;

/**
 *
 * @author adrianoska
 */
public class Device {

    String dvchost = null; //hostname
    String dvc = null; // IP address
    String log_format = null;
    String compress = null;
    String devConfPath = null;
    DevIndexer devIndexer = null;
    String threadName = null;
    Thread devIndexerThread = null;
    // The default format used by devices (CEF, CSV...)
    CefParser cefp = null;
    CsvParser csvp = null;
    JsnParser jsnp = null;
    SyslogParser sysp = null;

}
