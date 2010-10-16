package com.akiban.cserver;

public interface CServerConstants {
//    public final static short OK = 1;
//    public final static short END = 2;
//    public final static short ERR = 100;
//    public final static short MISSING_OR_CORRUPT_ROW_DEF = 99;
//    public final static short UNSUPPORTED_MODIFICATION = 98;
//    public final static short NOT_REALLY_AN_ERROR_ERROR = 42; //TODO
    
    public final static int DEFAULT_CSERVER_PORT = 5140;
    public final static String DEFAULT_CSERVER_HOST_STRING = "0.0.0.0";
    public final static String DEFAULT_CSERVER_PORT_STRING = Integer.toString(DEFAULT_CSERVER_PORT);

    public final static int MAX_VERSIONS_PER_TABLE = 65536;
    public final static int MAX_GROUP_DEPTH = 256;

}
