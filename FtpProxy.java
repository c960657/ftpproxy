/*
Java FTP Proxy Server 1.2.4
Copyright (C) 1998-2002 Christian Schmidt

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

Contributor(s): Rasjid Wilcox - support for masquerading of local IP


Find the latest version at http://christianschmidt.dk/ftpproxy
*/

import java.net.*;
import java.io.*;
import java.util.*;

public class FtpProxy extends Thread {
    private final static String configFile = "ftpproxy.conf";

    final static int DEFAULT_BACKLOG = 50;
    final static int DATABUFFERSIZE = 512;

    Socket skControlClient, skControlServer;
    BufferedReader brClient, brServer;
    PrintStream psClient, osServer;

    ServerSocket ssDataClient, ssDataServer;
    Socket skDataClient, skDataServer;

    //IP of interface facing client and server respectively
    String sLocalClientIP;
    String sLocalServerIP;

    private final Configuration config;

    DataConnect dcData;
    boolean serverPassive = false;

    final static Map lastPorts = new HashMap();
    
    //constants for debug output
    final static PrintStream pwDebug = System.out;
    final static String server2proxy = "S->P: ";
    final static String proxy2server = "S<-P: ";
    final static String proxy2client = "P->C: ";
    final static String client2proxy = "P<-C: ";
    final static String server2client = "S->C: ";
    final static String client2server = "S<-C: ";


    //use CRLF instead of println() to ensure that CRLF is used
    //on all platforms
    public static String CRLF = "\r\n";


    public FtpProxy(Configuration config, Socket skControlClient) {
        this.config = config;
        this.skControlClient = skControlClient;  

	//sLocalClientIP is initialized in main(), to handle
	//masquerade_host where the IP address for the host is dynamic.
    }       
    
    public static void main(String args[]) {
        //read configuration
        Properties properties = new Properties();
        try {
            properties.load(new FileInputStream(configFile));
        } catch (IOException e) {}

        Configuration config;
        try {      
            config = new Configuration(properties);
        } catch (Exception e) {
            System.err.println("Invalid configuration: " + e.getMessage());
            System.exit(0);
            return; //to make it compile
        }
        

       
        int port;
        if (args.length == 1) {
            port = Integer.parseInt(args[0]);

        } else if (args.length > 1) {
            System.err.println("Usage: java FtpProxy [port]");
            System.exit(0);
            return; //to make it compile

        } else {
            port = config.bindPort;
        }

        try {
            ServerSocket ssControlClient;
            
            if (config.bindAddress == null) {
                ssControlClient = new ServerSocket(port);
            } else {
                ssControlClient = new ServerSocket(port, DEFAULT_BACKLOG, config.bindAddress);
            }
             
            if (config.debug) pwDebug.println("Listening on port " + port);

            while (true) {
                Socket skControlClient = ssControlClient.accept();
                if (config.debug) pwDebug.println("New connection");
                new FtpProxy(config, skControlClient).start();
            }
        } catch (IOException e) {
            if (config.debug) {
                e.printStackTrace(pwDebug);
            } else {
                System.err.println(e.toString());
            }
        }
    }

    public void run() {
        try {
            brClient = new BufferedReader(new InputStreamReader(skControlClient.getInputStream()));
            psClient = new PrintStream(skControlClient.getOutputStream());

            if ((config.allowFrom != null && 
                 !isInSubnetList(config.allowFrom, skControlClient.getInetAddress())) ||
                isInSubnetList(config.denyFrom, skControlClient.getInetAddress())) {

                String toClient = config.msgOriginAccessDenied;
                psClient.print(toClient + CRLF);
                if (config.debug) pwDebug.println(proxy2client + toClient);
                skControlClient.close();
                return;
            }

	    try {
		if (config.masqueradeHostname == null) {
		    sLocalClientIP = skControlClient.getLocalAddress().getHostAddress().replace('.', ',');
		} else {
		    sLocalClientIP = InetAddress.getByName(config.masqueradeHostname.trim()).getHostAddress().replace('.', ',');
		}
	    } catch (UnknownHostException e) {
		String toClient = config.msgMasqHostDNSError;
		psClient.print(toClient + CRLF);
		if (config.debug) pwDebug.println(proxy2client + toClient);
		skControlClient.close();
		return;
	    }

            String username = null;
            String hostname = null;
            int serverport = 21;

            if (config.onlyAuto && config.autoHostname != null) {
                username = null; //value will not be used
                hostname = config.autoHostname;
                serverport = config.autoPort;

            } else {
                if (config.onlyAuto) { //and autoHostname == null
                    throw new RuntimeException("only_auto is enabled, but no auto_host is set");
                }
                
                String toClient = config.msgConnect;
                psClient.print(toClient + CRLF);
                psClient.flush();
                if (config.debug) pwDebug.println(proxy2client + toClient);
                
                //the username is read from the client
                String fromClient = brClient.readLine(); 
                if (config.debug) pwDebug.println(client2proxy + fromClient); 
                
                String userString = fromClient.substring(5);
                
                int a = userString.indexOf('@');
                int c = userString.lastIndexOf(':');
    
                if (a == -1 && config.isUrlSyntaxEnabled) {
                    int a1 = userString.indexOf('*');
                    if (a1 != -1) {
                        a = a1;
                        c = userString.lastIndexOf('*');
                        if (c == a) c = -1;
                    }
                }
                if (a == -1) {
                    username = userString;
                    hostname = config.autoHostname;
                    serverport = config.autoPort;
                } else if (c == -1) {
                    username = userString.substring(0, a);
                    hostname = userString.substring(a + 1);
                } else {
                    username = userString.substring(0, a);
                    hostname = userString.substring(a + 1, c);
                    serverport = Integer.parseInt(userString.substring(c + 1));
                }
            }
                
            //don't know which host to connect to
            if (hostname == null) {
                String toClient = config.msgIncorrectSyntax;
                if (config.debug) pwDebug.println(proxy2client + toClient);
                psClient.print(toClient + CRLF);
                skControlClient.close();
                return;
            }

            InetAddress serverAddress = InetAddress.getByName(hostname);

            if ((config.allowTo != null && 
                 !isInSubnetList(config.allowTo, serverAddress)) ||
                isInSubnetList(config.denyTo, serverAddress)) {

                String toClient = config.msgDestinationAccessDenied;
                
                psClient.print(toClient + CRLF);
                skControlClient.close();
                return;
            }

            serverPassive = config.useActive != null && !isInSubnetList(config.useActive, serverAddress) ||
                            isInSubnetList(config.usePassive, serverAddress);

            if (config.debug) pwDebug.println("Connecting to " + hostname + " on port " + serverport);
                                       
            try {
                skControlServer = new Socket(serverAddress, serverport);
            } catch (ConnectException e) {
                String toClient = config.msgConnectionRefused;
                psClient.print(toClient + CRLF);
                psClient.flush();
                if (config.debug) pwDebug.println(proxy2client + toClient);
                return;
            }
        
            brServer = new BufferedReader(new InputStreamReader(skControlServer.getInputStream()));
            osServer = new PrintStream(skControlServer.getOutputStream(), true);
            sLocalServerIP = skControlServer.getLocalAddress().getHostAddress().replace('.' ,',');

            if (!config.onlyAuto) {
                String fromServer = readResponseFromServer(false);
                
                if (fromServer.startsWith("421")) {
                    String toClient = fromServer;
                    psClient.print(toClient + CRLF);
                    psClient.flush();
                    return;
                }
                String toServer = "USER " + username;
                osServer.print(toServer + CRLF); //USER user
                osServer.flush();
                if (config.debug) pwDebug.println(proxy2server + toServer);
            }
            
            readResponseFromServer(true);

            for (;;) {
                String s = brClient.readLine();
                if (s == null) {
                    break;
                }
                readCommandFromClient(s);
            }

        } catch (Exception e) {
            String toClient = config.msgInternalError;
            if (config.debug) {
                pwDebug.println(proxy2client + toClient + e.toString());
                e.printStackTrace(pwDebug);
            }
            psClient.print(toClient + CRLF);
            psClient.flush();

        } finally {
            if (ssDataClient != null && !config.clientOneBindPort) {
                try {ssDataClient.close();} catch (IOException ioe) {}
            }
            if (ssDataServer != null && !config.serverOneBindPort) {
                try {ssDataServer.close();} catch (IOException ioe) {}
            }
            if (skDataClient != null) try {skDataClient.close();} catch (IOException ioe) {}
            if (skDataServer != null) try {skDataServer.close();} catch (IOException ioe) {}
            if (psClient != null) psClient.close();
            if (osServer != null) osServer.close();
            if (dcData != null) dcData.close();
        }
    }

    private void readCommandFromClient(String fromClient) throws IOException {
        String cmd = fromClient.toUpperCase();
        
        if (cmd.startsWith("PASV")) {
            if (config.debug) pwDebug.println(client2proxy + fromClient);

            if (ssDataClient != null && !config.clientOneBindPort) {
                try { ssDataClient.close(); } catch (IOException ioe) {}
            }
            if (skDataClient != null) try { skDataClient.close(); } catch (IOException ioe) {}
            if (dcData != null) dcData.close();

            if (ssDataClient == null || !config.clientOneBindPort) {
                ssDataClient = getServerSocket(config.clientBindPorts, skControlClient.getLocalAddress());
            }

            if (ssDataClient != null) {
                int port = ssDataClient.getLocalPort();

                String toClient = "227 Entering Passive Mode (" + sLocalClientIP + "," + 
                        (int) (port / 256) + "," + (port % 256) + ")";
                psClient.print(toClient + CRLF);
                psClient.flush();
                if (config.debug) pwDebug.println(proxy2client + toClient);
                    
                setupServerConnection(ssDataClient);

            } else {
                String toClient = "425 Cannot allocate local port.."; 
                psClient.print(toClient + CRLF);
                psClient.flush();
                if (config.debug) pwDebug.println(proxy2client + toClient);
            }

        } else if (cmd.startsWith("PORT")) {
            int port = parsePort(fromClient);

            if (ssDataClient != null && !config.clientOneBindPort) {
                try {ssDataClient.close();} catch (IOException ioe) {}
                ssDataClient = null;
            }
            if (skDataClient != null) try {skDataClient.close();} catch (IOException ioe) {}
            if (dcData != null) dcData.close();
            

            if (config.debug) pwDebug.println(client2proxy + fromClient);

            try {
                skDataClient = new Socket(skControlClient.getInetAddress(), port);

                String toClient = "200 PORT command successful.";
                psClient.print(toClient + CRLF);
                psClient.flush();
                if (config.debug) pwDebug.println(proxy2client + toClient);

                setupServerConnection(skDataClient);

            } catch (IOException e) {
                String toClient = "425 PORT command failed - try using PASV instead.";
                psClient.print(toClient + CRLF);
                psClient.flush();
                if (config.debug) pwDebug.println(proxy2client + toClient);

                return;
            }

             
        } else {
            osServer.print(fromClient + CRLF);
            osServer.flush();
            if (config.debug) {
                pwDebug.print(client2server);
                if (cmd.startsWith("PASS")) {
                    pwDebug.println("PASS *******");
                } else {
                    pwDebug.println(fromClient);
                }
            }

            readResponseFromServer(true);
        }
    }

    private String readResponseFromServer(boolean forwardToClient) throws IOException {
        String fromServer = brServer.readLine();
        String firstLine = fromServer;

        int response = Integer.parseInt(fromServer.substring(0, 3));
        if (fromServer.charAt(3) == '-') {
            String multiLine = fromServer.substring(0, 3) + ' ';
            while (!fromServer.startsWith(multiLine)) {
                if (forwardToClient) {
                    psClient.print(fromServer + CRLF);
                    psClient.flush();
                }
                if (config.debug) pwDebug.println((forwardToClient ? server2client : server2proxy) + fromServer);

                fromServer = brServer.readLine();
            }
        } 
        if (forwardToClient || response == 110) {
            psClient.print(fromServer + CRLF);
            psClient.flush();
        }
        if (config.debug) pwDebug.println((forwardToClient ? server2client : server2proxy) + fromServer);
        
        if (response >= 100 && response <= 199) {
            firstLine = readResponseFromServer(true);
        }

        return firstLine;
    }

    private void setupServerConnection(Object s) throws IOException {
	if (skDataServer != null) {
	    try {skDataServer.close();} catch (IOException ioe) {}
	}

        if (serverPassive) {
            String toServer = "PASV";
            osServer.print(toServer + CRLF);
            osServer.flush();
            if (config.debug) pwDebug.println(proxy2server + toServer);

            String fromServer = readResponseFromServer(false);
    
            int port = parsePort(fromServer);

            skDataServer = new Socket(skControlServer.getInetAddress(), port);
            
            (dcData = new DataConnect(s, skDataServer)).start();
        } else {
	    if (ssDataServer != null && !config.serverOneBindPort) {
		try {ssDataServer.close();} catch (IOException ioe) {}
	    }
    
            if (ssDataServer == null || !config.serverOneBindPort) {
                ssDataServer = getServerSocket(config.serverBindPorts, skControlServer.getLocalAddress());
            }
    
            if (ssDataServer != null) {
                int port = ssDataServer.getLocalPort();
                String toServer = "PORT " + sLocalServerIP + ',' + (int) (port / 256) + ',' + (port % 256);
        
                osServer.print(toServer + CRLF);
                osServer.flush();
                if (config.debug) pwDebug.println(proxy2server + toServer);
        
                readResponseFromServer(false);
    
                (dcData = new DataConnect(s, ssDataServer)).start();
                
            } else {
                String toClient = "425 Cannot allocate local port."; 
                psClient.print(toClient + CRLF);
                psClient.flush();
                if (config.debug) pwDebug.println(proxy2client + toClient);
            }
        }
    }

    public static boolean isInSubnetList(List list, InetAddress ia) {
        if (list == null) return false;
        
        for (Iterator iterator = list.iterator(); iterator.hasNext(); ) {
            Subnet subnet = (Subnet) iterator.next();
            
            if (subnet.isInSubnet(ia)) return true;
        }
        return false;
    }
    
    public static int parsePort(String s) throws IOException {
        int port;
        try {
            // (aa,bb,cc,dd,XXX,YYY).
            //             ^   ^   ^
            //            p1  p2  p3
            int p2 = s.lastIndexOf(',');
            int p3;
            for (p3 = p2 + 1; p3 < s.length() && Character.isDigit(s.charAt(p3)); p3++);
            int p1 = s.lastIndexOf(',', p2 - 1);
            port = Integer.parseInt(s.substring(p2 + 1, p3));
            port += 256 * Integer.parseInt(s.substring(p1 + 1, p2));
        } catch (Exception e) {
            throw new IOException();
        }
        return port;
    }

    public static synchronized ServerSocket getServerSocket(int[] portRanges, InetAddress ia) throws IOException {
        ServerSocket ss = null;
        if (portRanges != null) {
            int i; //current index of portRanges array
            int port;

            Integer lastPort = (Integer) lastPorts.get(portRanges);
            if (lastPort != null) {
                port = lastPort.intValue();
                for (i = 0; i < portRanges.length && port > portRanges[i + 1]; i += 2);
                port++;
            } else {
                port = portRanges[0];
                i = 0;
            }
            
            for (int lastTry = -2; port != lastTry; port++) {
                if (port > portRanges[i + 1]) {
                    i = (i + 2) % portRanges.length;
                    port = portRanges[i];
                }
                if (lastTry == -1) lastTry = port;
                try {
                    ss = new ServerSocket(port, 1, ia);
                    lastPorts.put(portRanges, new Integer(port));
                    break;
                } catch(BindException e) {
                    //port already in use
                }
            }
        } else {
            ss = new ServerSocket(0, 1, ia);
        }
        return ss;
    }



    public class DataConnect extends Thread {
        private byte buffer[] = new byte[DATABUFFERSIZE];
        private final Socket[] sockets = new Socket[2];
        private boolean isInitialized;
        private final Object[] o;

        //each argument may be either a Socket or a ServerSocket
        public DataConnect (Object o1, Object o2) {
            this.o = new Object[] {o1, o2};
        }

        public void run() {
            BufferedInputStream bis = null;
            BufferedOutputStream bos = null;
            try {
                int n = isInitialized ? 1 : 0;
                if (!isInitialized) {
                    for (int i = 0; i < 2; i++) {
                        if (o[i] instanceof ServerSocket) {
                            ServerSocket ss = (ServerSocket) o[i];
                            sockets[i] = ss.accept();
                            if (ss == ssDataServer && !config.serverOneBindPort ||
                                ss == ssDataClient && !config.clientOneBindPort) {

                                ss.close();
                            }
                        } else {
                            sockets[i] = (Socket) o[i];
                        }
                    }

                    isInitialized = true;
                    new Thread(this).start();
                }

                bis = new BufferedInputStream(sockets[n].getInputStream());
                bos = new BufferedOutputStream(sockets[1 - n].getOutputStream());

                for (;;) {
                    for (int i; (i = bis.read(buffer, 0, DATABUFFERSIZE)) != -1; ) {
                        bos.write(buffer, 0, i);
                    }
                    break;
                }
                bos.flush();
            } catch (SocketException e) {
                //socket closed
            } catch (IOException e) {
                if (config.debug) e.printStackTrace(pwDebug);
            }
            close();
        }
    
        public void close() {
            try { sockets[0].close(); } catch (Exception e) {}
            try { sockets[1].close(); } catch (Exception e) {}
        }
    }
}

class Configuration {
    int bindPort;
    InetAddress bindAddress;

    //variables read from configuration file
    boolean onlyAuto;
    String autoHostname;
    int autoPort;
    String masqueradeHostname;
    boolean isUrlSyntaxEnabled;
    int[] serverBindPorts;
    int[] clientBindPorts;
    boolean serverOneBindPort, clientOneBindPort;
    
    boolean debug;
    
    //lists of subnets
    List useActive, usePassive;
    List allowFrom, denyFrom, allowTo, denyTo;
    
    //messages
    String msgConnect;
    String msgConnectionRefused;
    String msgOriginAccessDenied;
    String msgDestinationAccessDenied;
    String msgIncorrectSyntax;
    String msgInternalError;
    String msgMasqHostDNSError;

    public Configuration(Properties properties) throws UnknownHostException {

        bindPort = Integer.parseInt(properties.getProperty("bind_port", "8089").trim());
        String ba = properties.getProperty("bind_address");
        bindAddress = ba == null ? null : InetAddress.getByName(ba.trim());
        
        serverBindPorts = readPortRanges(properties.getProperty("server_bind_ports"));
        clientBindPorts = readPortRanges(properties.getProperty("client_bind_ports"));
        serverOneBindPort = serverBindPorts != null && serverBindPorts.length == 2 &&
                            serverBindPorts[0] == serverBindPorts[1];
        clientOneBindPort = clientBindPorts != null && clientBindPorts.length == 2 && 
                            clientBindPorts[0] == clientBindPorts[1];

	masqueradeHostname = properties.getProperty("masquerade_host");
        if (masqueradeHostname != null) {
            //This is just to throw an UnknownHostException
            //if the config is incorrectly set up.
	    InetAddress.getByName(masqueradeHostname.trim());  
	}

        useActive  = readSubnets(properties.getProperty("use_active"));
        usePassive = readSubnets(properties.getProperty("use_passive"));
        allowFrom  = readSubnets(properties.getProperty("allow_from"));
        denyFrom   = readSubnets(properties.getProperty("deny_from"));
        allowTo    = readSubnets(properties.getProperty("allow_to"));
        denyTo     = readSubnets(properties.getProperty("deny_to"));
        onlyAuto   = properties.getProperty("only_auto", "0").trim().equals("1");
        autoHostname = properties.getProperty("auto_host");
        if (autoHostname != null) autoHostname = autoHostname.trim();
        autoPort = Integer.parseInt(properties.getProperty("auto_port", "21").trim());
        isUrlSyntaxEnabled = properties.getProperty("enable_url_syntax", "1").trim().equals("1");

        debug = properties.getProperty("output_debug_info", "0").trim().equals("1");


        msgConnect = "220 " + 
            properties.getProperty("msg_connect", 
                                   "Java FTP Proxy Server (usage: USERID=user@site) ready.");        
        msgConnectionRefused = "421 " + 
            properties.getProperty("msg_connection_refused", 
                                   "Connection refused, closing connection.");    
        msgOriginAccessDenied = "531 " + 
            properties.getProperty("msg_origin_access_denied", 
                                   "Access denied - closing connection.");                                       
        msgDestinationAccessDenied = "531 " + 
            properties.getProperty("msg_destination_access_denied", 
                                   "Access denied - closing connection."); 
        msgIncorrectSyntax = "531 " + 
            properties.getProperty("msg_incorrect_syntax", 
                                   "Incorrect usage - closing connection.");
        msgInternalError = "421 " + 
            properties.getProperty("msg_internal_error", 
                                   "Internal error, closing connection.");
	msgMasqHostDNSError = "421 " +
	    properties.getProperty("msg_masqerade_hostname_dns_error",
				   "Unable to resolve address for " + masqueradeHostname + " - closing connection.");
    }

    public static List readSubnets(String s) {
        if (s == null) return null;
        
        List list = new LinkedList();
        StringTokenizer st = new StringTokenizer(s.trim(), ",");
        while (st.hasMoreTokens()) {
            list.add(new Subnet(st.nextToken().trim()));
        }
        
        return list;
    }

    /**
     * Returns an array of length 2n, where n is the number of port
     * ranges specified. Index 2i will contain the first port number
     * in the i'th range, and index 2i+1 will contain the last.
     * E.g. the string "111,222-333,444-555,666" will result in the 
     * following array: {111, 111, 222, 333, 444, 555, 666, 666}
     */    
    public static int[] readPortRanges(String s) {
        if (s == null) return null;

        StringTokenizer st = new StringTokenizer(s.trim(), ",");
        int[] ports = new int[st.countTokens() * 2];
        
        if (ports.length == 0) return null;
        
        int lastPort = 0;
        for (int p = 0; st.hasMoreTokens(); p += 2) {
            String range = st.nextToken().trim();
            int i = range.indexOf('-');

            if (i == -1) {
                ports[p] = ports[p + 1] = Integer.parseInt(range);
            } else {
                ports[p] = Integer.parseInt(range.substring(0, i));
                ports[p + 1] = Integer.parseInt(range.substring(i + 1));
            }
            if (ports[p] < lastPort || ports[p] > ports[p + 1]) {
                throw new RuntimeException("Ports should be listed in increasing order.");
            }
            lastPort = ports[p + 1];
        }
        
        return ports;
    }
}            

