 package com.akiban.cserver;

 import javax.management.MBeanServerConnection;
 import javax.management.ObjectName;
 import javax.management.remote.JMXConnector;
 import javax.management.remote.JMXConnectorFactory;
 import javax.management.remote.JMXServiceURL;
  
  
public class CServerJmxManage {
  

    public static int jmxport;
    public static String jmxhost;
    private static String jmxurl; 

    private static JMXServiceURL url 		;
    private static JMXConnector jmxc 		;
    private static MBeanServerConnection mbsc 	;
    private static ObjectName mbean 		;

    public static void initJmx() throws Exception
    {
    	    if (jmxport==0) jmxport=Integer.parseInt(System.getProperty("jmxport"));
	    if (jmxhost==null) jmxhost= System.getProperty("jmxhost");
	    System.out.println ( "Using jmxhost=" + jmxhost + ", Using jmxport=" + jmxport);
    	    if (jmxurl==null) jmxurl = "service:jmx:rmi:///jndi/rmi://"+ jmxhost+":"+jmxport+"/jmxrmi";
	    System.out.println ( "Using jmxurl=" + jmxurl);
	    if (url==null)   url = new JMXServiceURL(jmxurl);
	    if (jmxc==null)  jmxc = JMXConnectorFactory.connect(url, null);
	    if (mbsc==null)  mbsc = jmxc.getMBeanServerConnection();
    	    if (mbean==null) mbean = new ObjectName("com.akiban:type=CSERVER");

    }
 
    public static String arrayToString(String[] a, String separator) {
	    StringBuffer result = new StringBuffer();
	    if (a.length > 0) {
		//        	result.append(a[0]);
	        for (int i=1; i<a.length; i++) {
        	    result.append(separator);
	            result.append(a[i]);
        	}
	    }
    	return result.toString();
    }  




    public static void main(String[] args)  
    {
	try{

 	    initJmx();    

	    if (args.length == 0  )
	    {
        	throw new Exception ("methodName must be the first argument");
	    }
  
	    String method = args[0];
	    int argcnt = args.length-1;
 
	    Object[] params = new Object[argcnt];
	    String[] signature = new String[argcnt];
	    for (int i = 1; i < args.length; i++ )
	    {
	
    		params[i-1]    = new String (args[i]);
	    	signature[i-1] = new String ("java.lang.String");
	    }
	
 	    Object retObj = mbsc.invoke(mbean, method, params,signature);
 	    System.out.println ( "JMX call " + method + "(" + arrayToString(args, ",")+ ")" + " returned : " + retObj);
	} catch (Exception e) {
		e.printStackTrace();
		System.exit(1);
	}
     }
}

