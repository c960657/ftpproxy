/*
Java FTP Proxy Server 1.0.4
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
import java.lang.*;
import java.io.*;

public class FtpProxy extends Thread {
    private Socket skControlClient, skControlServer;
    private BufferedReader brClient, brServer;
    private PrintWriter pwClient, pwServer;
    private ServerSocket ssDataClient, ssDataServer;
    private Socket skDataClient;
    private ControlConnect ccControl;
    private DataConnect dcData;
    private static int iSOTIMEOUT = 2000;
    private static int iDATASOTIMEOUT = 1000;
    private static int iDATABUFFERSIZE = 256;
    private String sLocalIP;

    private static String sServer = "Server> ";
    private static String sClient = "Client> ";
    private static String sProxy  = "Proxy > ";
    private static String sSpace  = "        ";

    public FtpProxy (Socket skControlClient) {
	this.skControlClient = skControlClient;
    }       
    
    public void run() {
	String sFromClient, sFromServer;

	ccControl = new ControlConnect();

	try {
	    brClient = new BufferedReader(new InputStreamReader(skControlClient.getInputStream()));
	    pwClient = new PrintWriter(skControlClient.getOutputStream(), true);
	    sLocalIP = skControlClient.getLocalAddress().getHostAddress().replace('.' ,',');

	    sFromServer = "220 Java FTP Proxy Server (usage: USERID=user@site) ready.";
	    pwClient.print(sFromServer + "\r\n");
	    pwClient.flush();
	    System.out.println(sProxy + sFromServer);
	    
	    sFromClient = brClient.readLine(); //should be "user@site"
	    System.out.println(sClient + sFromClient); 
	    
	    int i = sFromClient.indexOf('@');
	    int j = sFromClient.lastIndexOf(':');

	    if (i == -1) {
	    	i = sFromClient.indexOf('*');
	    	j = sFromClient.lastIndexOf('*');
	    	if (j == i) j = -1;
	    }
	    if (i <= 5 || i + 4 >= sFromClient.length()) {
		//simple validation of input
		sFromServer = "531 Incorrect usage - closing connection.";
		System.out.println(sProxy + sFromServer);
		pwClient.print(sFromServer + "\r\n");
		pwClient.flush();
		skControlClient.close();
		return;
	    }

	    String sUser = sFromClient.substring(0, i);
	    String sServerHost;
	    int iServerPort;
	    if (j == -1) {
	    	sServerHost = sFromClient.substring(i + 1);
	    	iServerPort = 21;
	    } else {
	    	sServerHost = sFromClient.substring(i + 1, j);
	    	iServerPort = Integer.parseInt(sFromClient.substring(j + 1));
	    }

	    System.out.println("Connecting to " + sServerHost + " on port " + iServerPort);
	    Socket skControlServer = new Socket(sServerHost, iServerPort);
	
	    brServer = new BufferedReader(new InputStreamReader(skControlServer.getInputStream()));
	    pwServer = new PrintWriter(skControlServer.getOutputStream(), true);
	
	    pwServer.print(sUser + "\r\n"); //USER user
	    pwServer.flush();
	    System.out.println(sProxy + sUser);
	    
	    skControlClient.setSoTimeout(iSOTIMEOUT);
	    skControlServer.setSoTimeout(iSOTIMEOUT);
	    ccControl.start();
	} catch (Exception e) {
	    try {
		sFromServer = "421 Internal error, closing connection.";
		System.out.println(sProxy + sFromServer + e.toString());
		pwClient.print(sFromServer + "\r\n");
		pwClient.flush();
	    } catch (Exception ioe) {}
	    ccControl.close();
	}
    }

    public static void main (String args[]) {
	int port = 8089;
	try {
	    if (args.length == 1) 
		port = Integer.parseInt(args[0]);
	    else if (args.length > 1)
		System.out.println("Usage: java FtpProxy [port]");
	} catch (NumberFormatException e) {}

			 try {
	    ServerSocket ssControlClient = new ServerSocket(port);
	    System.out.println("Listening on port " + port);

	    while (true) {
		Socket skControlClient = ssControlClient.accept();
		System.out.println("New connection");
		new FtpProxy(skControlClient).start();
	    }
			 } catch (IOException ioe) {
	    System.out.println(ioe.toString());
	}
    }
    
    public class ControlConnect extends Thread {
	private boolean bClosed = false;
	private boolean bIgnoreServer = true;

	private String readLine(BufferedReader br) throws IOException {
	    while (!bClosed)
		try { 
		    return br.readLine();
		} catch (InterruptedIOException iioe) {}
	    throw new IOException();
	}

	private synchronized void fromServer(String sFS) throws IOException {
	    String sFromClient, sFromServer = sFS;

	    if (!bIgnoreServer) {
		pwClient.print(sFromServer + "\r\n");
		pwClient.flush();
	    }
	    System.out.println(sServer + sFromServer);
	    if (sFromServer.charAt(3) != '-') bIgnoreServer = false;
	}

	private synchronized void fromClient(String sFC) throws IOException {
	    String sFromServer, sFromClient = sFC;

	    if (sFromClient == null) {
		close();
		return;
	    } else if (sFromClient.toUpperCase().startsWith("PASS")) {
		pwServer.print(sFromClient + "\r\n");
		pwServer.flush();
		System.out.println(sClient + "PASS *****");
	    } else if (sFromClient.toUpperCase().startsWith("PASV")) {
		System.out.println(sClient + sFromClient);
    
		try {if (ssDataClient != null) ssDataClient.close();} catch (IOException ioe) {}
		try {if (skDataClient != null) skDataClient.close();} catch (IOException ioe) {}
		try {if (ssDataServer != null) ssDataServer.close();} catch (IOException ioe) {}
		if (dcData != null) dcData.close();
		skDataClient = null;

		ssDataClient = new ServerSocket(0);

		int iPort = ssDataClient.getLocalPort();
		sFromServer = "227 Entering Passive Mode (" + sLocalIP + "," + (int)(iPort/256) + "," + (iPort % 256) + ")";
		pwClient.print(sFromServer + "\r\n");
		pwClient.flush();
		System.out.println(sProxy + sFromServer);
		    
		ssDataServer = new ServerSocket(0);

		iPort = ssDataServer.getLocalPort();
		sFromClient = "PORT " + sLocalIP + ',' + (int)(iPort/256) + ',' + (iPort % 256);

		pwServer.print(sFromClient + "\r\n");
		pwServer.flush();
		System.out.println(sProxy + sFromClient);

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
		} catch (IOException ioe) {
		    sFromServer = "500 PORT command failed - try using PASV instead.";
		    pwClient.print(sFromServer + "\r\n");
		    pwClient.flush();
		    System.out.println(sProxy + sFromServer);
    
		    System.out.println(ioe);
		    return;
		}

		ssDataServer = new ServerSocket(0);

		int iPort = ssDataServer.getLocalPort();
		sFromClient = "PORT " + sLocalIP + ',' + (int)(iPort/256) + ',' + (iPort % 256);

		pwServer.print(sFromClient + "\r\n");
		pwServer.flush();
		System.out.println(sProxy + sFromClient);

		(dcData = new DataConnect(skDataClient, ssDataServer)).start();
		 
	    } else {
		pwServer.print(sFromClient + "\r\n");
		pwServer.flush();
		System.out.println(sClient + sFromClient);
	    }
	}
    
	public void start() { 
	    super.start();
	    String sFromServer;
	    while (!bClosed)
		try {
		    if ((sFromServer = readLine(brServer)) == null) {
			break;
		    }
		    fromServer(sFromServer);
		} catch (IOException ioe) {
		    break;
		}
	    close();
	}

	public void run() {
	    String sFromClient;
	    while (!bClosed)
		try {
		    if ((sFromClient = readLine(brClient)) == null) {
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
		if (pwClient != null) pwClient.close();
		if (pwServer != null) pwServer.close();
		if (dcData != null) dcData.close();
	    }
	}
    }

    public class DataConnect extends Thread {
	private byte bRead[] = new byte[iDATABUFFERSIZE];
	private ServerSocket ss1, ss2;
	private Socket sk1, sk2;
	boolean bClosed, bUsed, bFirst = true;

	public DataConnect (ServerSocket ss1, ServerSocket ss2) throws SocketException {
	    this.ss1 = ss1;
	    this.ss2 = ss2;
	}

	public DataConnect (Socket sk1, ServerSocket ss2) throws SocketException {
	    this.sk1 = sk1;
	    this.ss2 = ss2;
	}

	public void run() {
	    BufferedInputStream bis=null;
	    BufferedOutputStream bos=null;
	    try {
		if (bFirst) {
		    bFirst = false;
		    if (ss1 != null) {
			sk1 = ss1.accept();
			ss1.close();
		    }
		    sk2 = ss2.accept();
		    ss2.close();

		    sk1.setSoTimeout(iDATASOTIMEOUT);
		    sk2.setSoTimeout(iDATASOTIMEOUT);

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
			while ((i = bis.read(bRead, 0, iDATABUFFERSIZE)) != -1) {
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

