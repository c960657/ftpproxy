/*
Java FTP Proxy Server 1.1.1
Copyright (C) 1998-2000 Christian Schmidt

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation; either version 2
of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
*/


import java.net.*;
import java.io.*;
import java.util.*;

public class FtpProxy extends Thread {
    private static String sDefaultConfigFile = "ftpproxy.conf";

    private static int DEFAULT_BACKLOG = 50;
    private static int SOTIMEOUT = 2000;
    private static int DATASOTIMEOUT = 1000;
    private static int DATABUFFERSIZE = 512;

    private Socket skControlClient, skControlServer;
    private BufferedReader rClient, rServer;
    private PrintStream osClient, osServer;
    private ServerSocket ssDataClient, ssDataServer;
    private Socket skDataClient;
    private ControlConnect ccControl;
    private DataConnect dcData;

    private static Properties pConfig = new Properties();

    //lists of subnets allowed and denied access
    private static List allowFrom;
    private static List denyFrom;
    private static List allowTo;
    private static List denyTo;

    //IP of interface facing client and server respectively
    private String sLocalClientIP, sLocalServerIP;

    //constants for debug output
    private static boolean debug = false;
    private static PrintStream pwDebug = System.out;
    private static String sServer = "Server> ";
    private static String sClient = "Client> ";
    private static String sProxy  = "Proxy > ";
    private static String sSpace  = "        ";

    //use CRLF instead of println() to ensure that CRLF is used
    //on all platforms
    public static String CRLF = "\r\n";

    public FtpProxy (Socket skControlClient) {
	this.skControlClient = skControlClient;
    }       
    
    public void run() {
	String sFromClient, sFromServer;

	ccControl = new ControlConnect();

	try {
	    rClient = new BufferedReader(new InputStreamReader(skControlClient.getInputStream()));
	    osClient = new PrintStream(skControlClient.getOutputStream());
	    sLocalClientIP = skControlClient.getLocalAddress().getHostAddress().replace('.' ,',');

	    if ((allowFrom != null && 
	    	 !isInSubnetList(allowFrom, skControlClient.getInetAddress())) ||
	        isInSubnetList(denyFrom, skControlClient.getInetAddress())) {

                sFromServer = "531 " + 
                    pConfig.getProperty("msg_origin_access_denied", 
                    			"Access denied - closing connection.");
	    	
                osClient.print(sFromServer + CRLF);
                if (debug) pwDebug.println(sProxy + sFromServer);
                skControlClient.close();
                return;
	    }

   	    boolean onlyAuto = pConfig.getProperty("only_auto", "0").equals("1");

	    String sUser = null;
            String sServerHost = null;
            int iServerPort = 21;

            String sAutoHost = pConfig.getProperty("auto_host");
	    int iAutoPort = Integer.parseInt(pConfig.getProperty("auto_port", "21"));
	    
	    if (onlyAuto && sAutoHost != null) {
                sUser = null; //value will not be used
                sServerHost = sAutoHost;
                iServerPort = iAutoPort;
	    } else {
	    	if (onlyAuto) { //and sAutoHost == null
	    	    throw new RuntimeException("only_auto is enabled, but no auto_host is set");
	    	}
	    	
                sFromServer = "220 " + pConfig.getProperty("msg_connect", 
                	"Java FTP Proxy Server (usage: USERID=user@site) ready.");
                osClient.print(sFromServer + CRLF);
                osClient.flush();
                if (debug) pwDebug.println(sProxy + sFromServer);
                
                //the username is read from the client
                sFromClient = rClient.readLine(); 
                if (debug) pwDebug.println(sClient + sFromClient); 
                
                int a = sFromClient.indexOf('@');
                int c = sFromClient.lastIndexOf(':');
    
                boolean enableUrlSyntax = 
                    pConfig.getProperty("enable_url_syntax", "1").equals("1");
    
                if (a == -1 && enableUrlSyntax) {
                    int a1 = sFromClient.indexOf('*');
                    if (a1 != -1) {
                    	a = a1;
	 		c = sFromClient.lastIndexOf('*');
	                if (c == a) c = -1;
	            }
                }
                if (a == -1) {
                    sUser = sFromClient;
		    sServerHost = sAutoHost;
		    iServerPort = iAutoPort;
                } else if (c == -1) {
                    sUser = sFromClient.substring(0, a);
                    sServerHost = sFromClient.substring(a + 1);
                } else {
                    sUser = sFromClient.substring(0, a);
                    sServerHost = sFromClient.substring(a + 1, c);
                    iServerPort = Integer.parseInt(sFromClient.substring(c + 1));
                }
	    } //if onlyAuto
                
	    //don't know which host to connect to
            if (sServerHost == null) {
            	sFromServer = "531 " + 
            	    pConfig.getProperty("msg_incorrect_syntax", 
           				"Incorrect usage - closing connection.");
                if (debug) pwDebug.println(sProxy + sFromServer);
                osClient.print(sFromServer + CRLF);
                skControlClient.close();
                return;
            }

	    InetAddress iaServerHost = InetAddress.getByName(sServerHost);

	    if ((allowTo != null && 
	    	 !isInSubnetList(allowTo, iaServerHost)) ||
	        isInSubnetList(denyTo, iaServerHost)) {

                sFromServer = "531 " + 
                    pConfig.getProperty("msg_destination_access_denied", 
                    			"Access denied - closing connection."); 
	    	
                osClient.print(sFromServer + CRLF);
                skControlClient.close();
                return;
	    }

	    if (debug) pwDebug.println("Connecting to " + sServerHost + 
				       " on port " + iServerPort);
	    skControlServer = new Socket(iaServerHost, iServerPort);
	
	    rServer = new BufferedReader(new InputStreamReader(skControlServer.getInputStream()));
	    osServer = new PrintStream(skControlServer.getOutputStream(), true);
	    sLocalServerIP = skControlServer.getLocalAddress().getHostAddress().replace('.' ,',');

	    if (onlyAuto) {
	    	ccControl.setIgnoreServer(false);
	    } else {
                osServer.print(sUser + CRLF); //USER user
                osServer.flush();
                if (debug) pwDebug.println(sProxy + sUser);
	    }
	    
	    skControlClient.setSoTimeout(SOTIMEOUT);
	    skControlServer.setSoTimeout(SOTIMEOUT);
	    ccControl.start();
	} catch (ConnectException e) {
	    sFromServer = "421 " + 
            	    pConfig.getProperty("msg_connection_refused", 
           				"Connection refused, closing connection.");
	    osClient.print(sFromServer + CRLF);
	    osClient.flush();
	    if (debug) pwDebug.println(sProxy + sFromServer + e.toString());
	    ccControl.close();
	} catch (Exception e) {
	    sFromServer = "421 " + 
            	    pConfig.getProperty("msg_internal_error", 
           				"Internal error, closing connection.");
	    if (debug) pwDebug.println(sProxy + sFromServer + e.toString());
	    osClient.print(sFromServer + CRLF);
	    osClient.flush();
	    ccControl.close();
	}
    }

    public static void main (String args[]) {
	try {
            pConfig.load(new FileInputStream(sDefaultConfigFile));
        } catch (IOException e) {
            //use defaults
        }   
        allowFrom = readSubnets("allow_from");
        denyFrom = readSubnets("deny_from");
        allowTo = readSubnets("allow_to");
        denyTo = readSubnets("deny_to");
	int port;

	if (args.length == 1) {
	    port = Integer.parseInt(args[0]);
	} else if (args.length > 1) {
	    System.out.println("Usage: java FtpProxy [port]");
	    System.exit(0);
	    return; //to make it compile
	} else {
	    port = Integer.parseInt(pConfig.getProperty("port", "8089"));
	}

        try {
	    ServerSocket ssControlClient;
	    
	    String sBindAddress = pConfig.getProperty("bind_address");
	    if (sBindAddress == null) {
	    	ssControlClient = new ServerSocket(port);
	    } else {
	    	InetAddress bindAddr = InetAddress.getByName(sBindAddress);
		ssControlClient = new ServerSocket(port, DEFAULT_BACKLOG, bindAddr);
	    }
	     
	    if (debug) pwDebug.println("Listening on port " + port);

	    while (true) {
		Socket skControlClient = ssControlClient.accept();
		if (debug) pwDebug.println("New connection");
		new FtpProxy(skControlClient).start();
	    }
	} catch (IOException e) {
	    System.err.println(e.toString());
	}
    }

    private static List readSubnets(String fieldName) {
    	String field = pConfig.getProperty(fieldName);
    	if (field == null) return null;
    	
    	List v = new LinkedList();
        StringTokenizer st = new StringTokenizer(field, ",");
	while (st.hasMoreTokens()) {
	    v.add(new Subnet(st.nextToken()));
        }
        
        return v;
    }
    
    private static boolean isInSubnetList(List list, InetAddress ia) {
    	if (list == null) return false;
    	
    	for (Iterator iterator = list.iterator(); iterator.hasNext(); ) {
    	    Subnet subnet = (Subnet) iterator.next();
    	    
    	    if (subnet.isInSubnet(ia)) return true;
    	}
    	return false;
    }
    
    public class ControlConnect extends Thread {
	private boolean bClosed = false;
	
	//don't send next reply from server to client
	private boolean bIgnoreServer = true; 

	private String readLine(BufferedReader br) throws IOException {
	    while (!bClosed)
		try { 
		    return br.readLine();
		} catch (InterruptedIOException iioe) {}
	    throw new IOException();
	}

	public void setIgnoreServer(boolean bIgnoreServer) {
	    this.bIgnoreServer = bIgnoreServer;
	}

	private void fromServer() throws IOException {
	    String sFromServer;

            String multiLine = "";
            do {
                sFromServer = readLine(rServer);
                synchronized (this) {
                    if (sFromServer == null) {
                        return;
                    }
                    if (multiLine.length() == 0) {
                        if (sFromServer.charAt(3) == '-') {
                            multiLine = sFromServer.substring(0, 3) + ' ';
                        }
                    }
                    if (!bIgnoreServer) {
                        osClient.print(sFromServer + CRLF);
                        osClient.flush();
                    }
                    if (debug) pwDebug.println(sServer + sFromServer);
                }
            } while (!sFromServer.startsWith(multiLine));
            bIgnoreServer = false;
	}

	private synchronized void fromClient(String sFC) throws IOException {
	    String sFromServer, sFromClient = sFC;

	    if (sFromClient == null) {
		close();
		return;
	    } else if (sFromClient.toUpperCase().startsWith("PASS")) {
		osServer.print(sFromClient + CRLF);
		osServer.flush();
		if (debug) pwDebug.println(sClient + "PASS *****");
	    } else if (sFromClient.toUpperCase().startsWith("PASV")) {
		if (debug) pwDebug.println(sClient + sFromClient);
    
		try { if (ssDataClient != null) ssDataClient.close(); } catch (IOException ioe) {}
		try { if (skDataClient != null) skDataClient.close(); } catch (IOException ioe) {}
		try { if (ssDataServer != null) ssDataServer.close(); } catch (IOException ioe) {}

		if (dcData != null) dcData.close();
		skDataClient = null;

		ssDataClient = new ServerSocket(0, 1, skControlClient.getLocalAddress());

		int iPort = ssDataClient.getLocalPort();
		sFromServer = "227 Entering Passive Mode (" + sLocalClientIP + "," + (int)(iPort/256) + "," + (iPort % 256) + ")";
		osClient.print(sFromServer + CRLF);
		osClient.flush();
		if (debug) pwDebug.println(sProxy + sFromServer);
		    
		ssDataServer = new ServerSocket(0, 1, skControlServer.getLocalAddress());

		iPort = ssDataServer.getLocalPort();
		sFromClient = "PORT " + sLocalServerIP + ',' + (int)(iPort/256) + ',' + (iPort % 256);

		osServer.print(sFromClient + CRLF);
		osServer.flush();
		if (debug) pwDebug.println(sProxy + sFromClient);

		bIgnoreServer = true;

		(dcData = new DataConnect(ssDataClient, ssDataServer)).start();
    
	    } else if (sFromClient.toUpperCase().startsWith("PORT")) {
		int iClientPort, i;
		try {
		    i = sFromClient.lastIndexOf(',');
		    iClientPort = Integer.parseInt(sFromClient.substring(i+1));
		    iClientPort += 256 * Integer.parseInt(sFromClient.substring(sFromClient.lastIndexOf(',', i-1)+1, i));
		} catch (Exception e) {throw new IOException();}

		if (ssDataClient != null) try {ssDataClient.close();} catch (IOException ioe) {}
		if (skDataClient != null) try {skDataClient.close();} catch (IOException ioe) {}
		if (ssDataServer != null) try {ssDataServer.close();} catch (IOException ioe) {}
		if (dcData != null) dcData.close();
		ssDataClient = null;

		try {
		    skDataClient = new Socket(skControlClient.getInetAddress(), iClientPort);
		} catch (IOException e) {
		    sFromServer = "500 PORT command failed - try using PASV instead.";
		    osClient.print(sFromServer + CRLF);
		    osClient.flush();
		    if (debug) pwDebug.println(sProxy + sFromServer);
    
		    System.err.println(e);
		    return;
		}

		ssDataServer = new ServerSocket(0);

		int iPort = ssDataServer.getLocalPort();
		sFromClient = "PORT " + sLocalServerIP + ',' + (int)(iPort/256) + ',' + (iPort % 256);

		osServer.print(sFromClient + CRLF);
		osServer.flush();
		if (debug) pwDebug.println(sProxy + sFromClient);

		(dcData = new DataConnect(skDataClient, ssDataServer)).start();
		 
	    } else {
		osServer.print(sFromClient + CRLF);
		osServer.flush();
		if (debug) pwDebug.println(sClient + sFromClient);
	    }
	}
    
	public void start() { 
	    super.start();
	    String sFromServer;
	    while (!bClosed)
		try {
		    fromServer();
		} catch (IOException ioe) {
		    break;
		}
	    close();
	}

	public void run() {
	    String sFromClient;
	    while (!bClosed)
		try {
		    if ((sFromClient = readLine(rClient)) == null) {
			break;
		    }
		    fromClient(sFromClient);
		} catch (IOException ioe) {
		    break;
		}
	    close();
	}
    
	public void close() {
	    if (!bClosed) {
		bClosed = true;
		if (ssDataClient != null) try {ssDataClient.close();} catch (IOException ioe) {}
		if (ssDataServer != null) try {ssDataServer.close();} catch (IOException ioe) {}
		if (osClient != null) osClient.close();
		if (osServer != null) osServer.close();
		if (dcData != null) dcData.close();
	    }
	}
    }

    public class DataConnect extends Thread {
	private byte bRead[] = new byte[DATABUFFERSIZE];
	private ServerSocket ss1, ss2;
	private Socket sk1, sk2;
	private boolean bClosed, bUsed, bFirst = true;

	public DataConnect (ServerSocket ss1, ServerSocket ss2) throws SocketException {
	    this.ss1 = ss1;
	    this.ss2 = ss2;
	}

	public DataConnect (Socket sk1, ServerSocket ss2) throws SocketException {
	    this.sk1 = sk1;
	    this.ss2 = ss2;
	}

	public void run() {
	    BufferedInputStream bis = null;
	    BufferedOutputStream bos = null;
	    try {
		if (bFirst) {
		    bFirst = false;
		    if (ss1 != null) {
			sk1 = ss1.accept();
			ss1.close();
		    }
		    sk2 = ss2.accept();
		    ss2.close();

		    sk1.setSoTimeout(DATASOTIMEOUT);
		    sk2.setSoTimeout(DATASOTIMEOUT);

		    bis = new BufferedInputStream(sk1.getInputStream());
		    bos = new BufferedOutputStream(sk2.getOutputStream());

		    new Thread(this).start();
		} else {
		    bis = new BufferedInputStream(sk2.getInputStream());
		    bos = new BufferedOutputStream(sk1.getOutputStream());
		}

		int i;
		boolean bUsedLocal = false ;

		while (!bClosed) 
		    try {
			while ((i = bis.read(bRead, 0, DATABUFFERSIZE)) != -1) {
			    bos.write(bRead, 0, i);
			    bUsedLocal = true;
			    bUsed = true;
			}
			break;
		    } catch (InterruptedIOException iioe) {
			if (bUsed && !bUsedLocal) return; //use data connection for EITHER send or receive
		    }

		bos.flush();
	    } catch (SocketException e) {
	    	//socket is closed
	    } catch (IOException e) {
		System.err.println(e);
	    }
	    try {bis.close();} catch (Exception e) {}
	    try {bos.close();} catch (Exception e) {}
	    close();
	}
    
	public void close() {
	    if (!bClosed) {
		bClosed = true;
		try {sk1.close();} catch (Exception e) {}
		try {sk2.close();} catch (Exception e) {}
	    }
	}
    }
}

