package com.sdt16.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ServerSocketFactory;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;

public class Proxy {
	
	private static final int PORT = 5034;

	/**
	 * @param args
	 * @throws IOException 
	 */
	public static void main(String[] args) throws IOException {
		ExecutorService threadPool = Executors.newFixedThreadPool(5);
		ServerSocket serverSocket = ServerSocketFactory.getDefault().createServerSocket(PORT);
		while (true) {
			Socket localSkt = serverSocket.accept();
			threadPool.execute(new Proxy.HandleConnection(localSkt));
		}

	}
	
	public static class HandleConnection implements Runnable {

		Proxy proxy = new Proxy();
		Socket localSkt;
		
		public HandleConnection(Socket localSkt) {
			this.localSkt = localSkt;
		}
		
		@Override
		public void run() {
			
			try {
				//IOUtils.copy(localSkt.getInputStream(), System.out);
				List<String> headers = proxy.readHeaders(localSkt.getInputStream());
				
				Iterator<String> headerIter = headers.iterator();
				if (!headerIter.hasNext()) {
					throw new IOException("Empty Request");
				}
				
				String first = headerIter.next();
				HashMap<String, String> parsedHeaders = proxy.parseHeaders(headerIter);
				
				proxy.addForwardedForHeader(localSkt, parsedHeaders);
				
				Socket remoteSkt = proxy.getRemoteConnection(first, parsedHeaders);
				
				proxy.writeHeadersToSocket(remoteSkt, first, parsedHeaders);
				
				List<String> returnHeaders = proxy.readHeaders(remoteSkt.getInputStream());
				
				Iterator<String> returnHeaderIter = returnHeaders.iterator();
				if (!returnHeaderIter.hasNext()) {
					throw new IOException("Empty Response");
				}
				String firstReturn = returnHeaderIter.next();
				HashMap<String, String> parsedReturnHeaders = proxy.parseHeaders(returnHeaderIter);
				
				proxy.writeHeadersToSocket(localSkt, firstReturn, parsedReturnHeaders);
				
				String httpCode = firstReturn.substring(9, 12);
				if (httpCode.equals("200") || httpCode.equals("500") ) {
					if ("chunked".equals(parsedReturnHeaders.get("Transfer-Encoding"))) {
						proxy.sendChunkedData(remoteSkt.getInputStream(), remoteSkt, localSkt, returnHeaders);
					}

					if (parsedReturnHeaders.get("Content-Length") != null) {
						proxy.sendContentLengthData(remoteSkt.getInputStream(), localSkt, parsedReturnHeaders);
					}
				}
				localSkt.close();
				remoteSkt.close();
				
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		
	}

	public void sendContentLengthData(InputStream inputStream, Socket localSkt, HashMap<String, String> returnHeaders) throws IOException {

		long contentLength = Long.parseLong(returnHeaders.get("Content-Length"));
		IOUtils.copyLarge(inputStream, localSkt.getOutputStream(), 0L, contentLength);
	}
	public void sendChunkedData(InputStream inputStream,
			Socket remoteSkt, Socket localSkt, List<String> returnHeaders) throws IOException {
		//IOUtils.copy(reader, System.out);
		byte[] charBuf = new byte[1];
		charBuf[0] = Character.MIN_VALUE;
		while (true) {
			StringBuilder sb = new StringBuilder();
			while (true) {
				inputStream.read(charBuf);
				if (charBuf[0] != '\n' && charBuf[0] != '\r') {
					sb.append(new String(charBuf));
				} else {
					inputStream.read(charBuf); //read second part of CRLF.
					break;
				}
			}

			long bytesToRead = Long.parseLong(sb.toString(), 16);
			StringReader sr = new StringReader(Long.toHexString(bytesToRead) + "\n");
			IOUtils.copy(sr, localSkt.getOutputStream());
			IOUtils.copyLarge(inputStream, localSkt.getOutputStream(), 0L, bytesToRead+2); //plus 2 for ending crlf.
			if (bytesToRead == 0) {
				return;
			}
		}

		
		
	}

	private void writeHeadersToSocket(Socket skt, String first,
			HashMap<String, String> parsedHeaders) throws IOException {
		StringBuilder sb = new StringBuilder();
		sb.append(first + "\n");
		for (Map.Entry<String, String> entry: parsedHeaders.entrySet()) {
			sb.append(entry.getKey());
			sb.append(": ");
			sb.append(entry.getValue());
			sb.append("\n");
		}
		sb.append("\n");
		
		IOUtils.write(sb.toString(), skt.getOutputStream());
	}

	private Socket getRemoteConnection(String first,
			HashMap<String, String> parsedHeaders) throws IOException {
		Socket remoteSocket;
		String host = parsedHeaders.get("Host");
		String[] hostAndPort = StringUtils.split(host, ":");
		InetAddress remoteAddress = InetAddress.getByName(hostAndPort[0]);
		if (hostAndPort.length == 2) {
			remoteSocket = new Socket(remoteAddress, Integer.parseInt(hostAndPort[1]));
		} else {
			remoteSocket = new Socket(remoteAddress, 80);
		}
		
		return remoteSocket;
		
		
		
	}

	private void addForwardedForHeader(Socket localSkt,
			HashMap<String, String> parsedHeaders) {
		if (parsedHeaders.get("X-Forwarded-For") == null) {
			parsedHeaders.put("X-Forwarded-For", localSkt.getInetAddress().toString());
		}
	}

	private HashMap<String, String> parseHeaders(Iterator<String> headerIter) {
		HashMap<String, String> parsedHeaders = new LinkedHashMap<String, String>();
		while (headerIter.hasNext()) {
			String currHeader = headerIter.next();
			String[] splitHeader = currHeader.split(": ", 2);
			if (splitHeader.length == 2){
				parsedHeaders.put(splitHeader[0], splitHeader[1]);
			}
		}
		return parsedHeaders;
	}

	private List<String> readHeaders(InputStream inputStream) throws IOException {
		List<String> toReturn = new LinkedList<String>();
		while(true) {
			StringBuilder sb = new StringBuilder();
			byte[] buf = new byte[1];
			buf[0] = Character.MIN_VALUE;
			while(true) {
				int code = inputStream.read(buf);
				if (code == -1 || buf[0] == '\r') {
					break;
				}
				sb.append(new String(buf));
			}
			inputStream.read(buf);
			if (sb.length() == 0) {
				break;
			}
			toReturn.add(sb.toString());
		}	
		
		return toReturn;
	}

}
