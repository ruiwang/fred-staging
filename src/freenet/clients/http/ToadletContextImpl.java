package freenet.clients.http;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;

import freenet.clients.http.annotation.AllowData;
import freenet.l10n.NodeL10n;
import freenet.support.HTMLEncoder;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.MultiValueTable;
import freenet.support.TimeUtil;
import freenet.support.URIPreEncoder;
import freenet.support.api.Bucket;
import freenet.support.api.BucketFactory;
import freenet.support.api.HTTPRequest;
import freenet.support.io.BucketTools;
import freenet.support.io.FileUtil;
import freenet.support.io.LineReadingInputStream;
import freenet.support.io.TooLongException;
/**
 * ToadletContext implementation, including all the icky HTTP parsing etc.
 * An actual ToadletContext object represents a request, after we have parsed the 
 * headers. It provides methods to send replies.
 * @author root
 *
 */
public class ToadletContextImpl implements ToadletContext {
	
	private static final Class<?> HANDLE_PARAMETERS[] = new Class[] {URI.class, HTTPRequest.class, ToadletContext.class};

	/* methods listed here are *not* configurable with
	 * AllowData annotation
	 */
	private static final String METHODS_MUST_HAVE_DATA = "POST";
	private static final String METHODS_CANNOT_HAVE_DATA = "GET";
	private static final String METHODS_RESTRICTED_MODE = "GET POST";
	
	private final MultiValueTable<String,String> headers;
	private ArrayList<ReceivedCookie> cookies; // Null until the first time the user queries us for a ReceivedCookie.
	private ArrayList<Cookie> replyCookies; // Null until the first time the user sets a Cookie.
	private final OutputStream sockOutputStream;
	private final PageMaker pagemaker;
	private final BucketFactory bf;
	private final ToadletContainer container;
	private final InetAddress remoteAddr;
	private boolean sentReplyHeaders;
	private volatile Toadlet activeToadlet;
	
	/** Is the context closed? If so, don't allow any more writes. This is because there
	 * may be later requests.
	 */
	private boolean closed;
	private boolean shouldDisconnect;
	
	public ToadletContextImpl(Socket sock, MultiValueTable<String,String> headers, BucketFactory bf, PageMaker pageMaker, ToadletContainer container) throws IOException {
		this.headers = headers;
		this.cookies = null;
		this.replyCookies = null;
		this.closed = false;
		sockOutputStream = sock.getOutputStream();
		remoteAddr = sock.getInetAddress();
		if(Logger.shouldLog(Logger.DEBUG, this))
			Logger.debug(this, "Connection from "+remoteAddr);
		this.bf = bf;
		this.pagemaker = pageMaker;
		this.container = container;
	}
	
	private void close() {
		closed = true;
	}
	
	private void sendMethodNotAllowed(String method, boolean shouldDisconnect) throws ToadletContextClosedException, IOException {
		if(closed) throw new ToadletContextClosedException();
		MultiValueTable<String,String> mvt = new MultiValueTable<String,String>();
		mvt.put("Allow", "GET, PUT");
		sendError(sockOutputStream, 405, "Method Not Allowed", l10n("methodNotAllowed"), shouldDisconnect, mvt);
	}
	
	private static String l10n(String key) {
		return NodeL10n.getBase().getString("ToadletContextImpl."+key);
	}

	private static String l10n(String key, String pattern, String value) {
		return NodeL10n.getBase().getString("ToadletContextImpl."+key, new String[] { pattern }, new String[] { value });
	}

	/**
	 * Send an error message. Caller provides the HTTP code, reason string, and a message, which
	 * will become the title and the h1'ed contents of the error page. 
	 */
	private static void sendError(OutputStream os, int code, String httpReason, String message, boolean shouldDisconnect, MultiValueTable<String,String> mvt) throws IOException {
		sendHTMLError(os, code, httpReason, "<html><head><title>"+message+"</title></head><body><h1>"+message+"</h1></body>", shouldDisconnect, mvt);
	}
	
	/**
	 * Send an error message, containing full HTML from a String.
	 * @param os The OutputStream to send the message to.
	 * @param code The HTTP status code.
	 * @param httpReason The HTTP reason string for the HTTP status code. Do not make stuff up,
	 * use the official reason string, or some browsers may break.
	 * @param htmlMessage The HTML string to send.
	 * @param disconnect Whether to disconnect from the client afterwards.
	 * @param mvt Any additional headers.
	 * @throws IOException If we could not send the error message.
	 */
	private static void sendHTMLError(OutputStream os, int code, String httpReason, String htmlMessage, boolean disconnect, MultiValueTable<String,String> mvt) throws IOException {
		if(mvt == null) mvt = new MultiValueTable<String,String>();
		byte[] messageBytes = htmlMessage.getBytes("UTF-8");
		sendReplyHeaders(os, code, httpReason, mvt, "text/html; charset=UTF-8", messageBytes.length, null, disconnect);
		os.write(messageBytes);
	}
	
	private void sendNoToadletError(boolean shouldDisconnect) throws ToadletContextClosedException, IOException {
		if(closed) throw new ToadletContextClosedException();
		sendError(sockOutputStream, 404, "Not Found", l10n("noSuchToadlet"), shouldDisconnect, null);
	}
	
	private static void sendURIParseError(OutputStream os, boolean shouldDisconnect, Throwable e) throws IOException {
		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw);
		e.printStackTrace(pw);
		pw.close();
		String message = "<html><head><title>"+l10n("uriParseErrorTitle")+"</title></head><body><p>"+HTMLEncoder.encode(e.getMessage())+"</p><pre>\n"+sw.toString();
		sendHTMLError(os, 400, "Bad Request", message, shouldDisconnect, null);
	}
	
	public void sendReplyHeaders(int replyCode, String replyDescription, MultiValueTable<String,String> mvt, String mimeType, long contentLength) throws ToadletContextClosedException, IOException {
		sendReplyHeaders(replyCode, replyDescription, mvt, mimeType, contentLength, null);
	}
	
	public void sendReplyHeaders(int replyCode, String replyDescription, MultiValueTable<String,String> mvt, String mimeType, long contentLength, Date mTime) throws ToadletContextClosedException, IOException {
		if(closed) throw new ToadletContextClosedException();
		if(sentReplyHeaders) {
			throw new IllegalStateException("Already sent headers!");
		}
		sentReplyHeaders = true;
		
		if(replyCookies != null) {
			mvt.put("cache-control:", "no-cache=\"set-cookie\"");
			
			final boolean logMINOR = Logger.shouldLog(Logger.MINOR, ToadletContextImpl.class);

			// We do NOT use "set-cookie2" even though we should according though RFC2965 - Firefox 3.0.14 ignores it for me!
			
			for(Cookie cookie : replyCookies) {
				final String cookieHeader = cookie.encodeToHeaderValue();
				mvt.put("set-cookie", cookieHeader);
				if(logMINOR)
					Logger.minor(this, "set-cookie: " + cookieHeader);
			}
		}
		
		sendReplyHeaders(sockOutputStream, replyCode, replyDescription, mvt, mimeType, contentLength, mTime, shouldDisconnect);
	}
	
	public PageMaker getPageMaker() {
		return pagemaker;
	}
	
	public MultiValueTable<String,String> getHeaders() {
		return headers;
	}
	
	private void parseCookies() throws ParseException {
		if(cookies != null)
			return;
		
		int cookieAmount = headers.countAll("cookie");
		
		if(cookieAmount == 0)
			return;
		
		cookies = new ArrayList<ReceivedCookie>(cookieAmount + 1);
		
		for(String cookieHeader : headers.iterateAll("cookie")) {
			ArrayList<ReceivedCookie> parsedCookies = ReceivedCookie.parseHeader(cookieHeader);
			cookies.addAll(parsedCookies);
		}
	}
	
	public ReceivedCookie getCookie(URI domain, URI path, String name) throws ParseException {
		parseCookies();
		
		if(cookies == null) // There are no cookies.
			return null;
		
		name = name.toLowerCase();
		
		//String stringDomain = domain==null ? null : domain.toString().toLowerCase();
		//String stringPath = path.toString();
		
		// RFC2965: Two cookies are equal if name and domain are equal with case-insensitive comparison and path is equal with case-sensitive comparison.
		//getName() / getDomain() returns lowercase and getPath() returns the original path.
		
		// UNFORTUNATELY firefox will ONLY give us the name and the value of the cookie, so we ignore everything else.
		
		for(ReceivedCookie cookie : cookies) {
			try {
			//if(stringDomain != null) {
			//	URI cookieDomain = cookie.getDomain();
			//	
			//	if(cookieDomain==null || !stringDomain.equals(cookieDomain.toString()))
			//		continue;
			//}
			//
			//if(cookie.getPath().toString().equals(stringPath) && cookie.getName().equals(name))
			//	return cookie;
				
				if(cookie.getName().equals(name))
					return cookie;
			}
			catch(RuntimeException e) {
				Logger.error(this, "Error in cookie", e);
			}
		}
		
		return null;
	}
	
	public void setCookie(Cookie newCookie) {
		if(replyCookies == null)
			replyCookies = new ArrayList<Cookie>(4);
		
		replyCookies.add(newCookie);
	}
	
	static void sendReplyHeaders(OutputStream sockOutputStream, int replyCode, String replyDescription, MultiValueTable<String,String> mvt, String mimeType, long contentLength, Date mTime, boolean disconnect) throws IOException {
		
		// Construct headers
		if(mvt == null)
			mvt = new MultiValueTable<String,String>();
		if(mimeType != null)
			if(mimeType.equalsIgnoreCase("text/html")){
				mvt.put("content-type", mimeType+"; charset=UTF-8");
			}else{
				mvt.put("content-type", mimeType);
			}
		if(contentLength >= 0)
			mvt.put("content-length", Long.toString(contentLength));
		
		String expiresTime;
		if (mTime == null) {
			expiresTime = "Thu, 01 Jan 1970 00:00:00 GMT";
		} else {
			// use an expiry time of 1 day, somewhat arbitrarily
			expiresTime = TimeUtil.makeHTTPDate(mTime.getTime() + (24 * 60 * 60 * 1000));
		}
		mvt.put("expires", expiresTime);
		
		String nowString = TimeUtil.makeHTTPDate(System.currentTimeMillis());
		String lastModString;
		if (mTime == null) {
			lastModString = nowString;
		} else {
			lastModString = TimeUtil.makeHTTPDate(mTime.getTime());
		}
		
		mvt.put("last-modified", lastModString);
		mvt.put("date", nowString);
		if (mTime == null) {
			mvt.put("pragma", "no-cache");
			mvt.put("cache-control", "max-age=0, must-revalidate, no-cache, no-store, post-check=0, pre-check=0");
		}
		if(disconnect)
			mvt.put("connection", "close");
		else
			mvt.put("connection", "keep-alive");
		StringBuilder buf = new StringBuilder(1024);
		buf.append("HTTP/1.1 ");
		buf.append(replyCode);
		buf.append(' ');
		buf.append(replyDescription);
		buf.append("\r\n");
		for(Enumeration<String> e = mvt.keys();e.hasMoreElements();) {
			String key = e.nextElement();
			Object[] list = mvt.getArray(key);
			key = fixKey(key);
			for(int i=0;i<list.length;i++) {
				String val = (String) list[i];
				buf.append(key);
				buf.append(": ");
				buf.append(val);
				buf.append("\r\n");
			}
		}
		buf.append("\r\n");
		sockOutputStream.write(buf.toString().getBytes("US-ASCII"));
	}
	
	/** Fix key case to be conformant to HTTP expectations.
	 * Note that HTTP is case insensitive on header names, but we may as well
	 * send something as close to the spec as possible in case of broken clients... 
	 */
	private static String fixKey(String key) {
		StringBuilder sb = new StringBuilder(key.length());
		char prev = 0;
		for(int i=0;i<key.length();i++) {
			char c = key.charAt(i);
			if((i == 0) || (prev == '-')) {
				c = Character.toUpperCase(c);
			}
			sb.append(c);
			prev = c;
		}
		return sb.toString();
	}
	
	/**
	 * Handle an incoming connection. Blocking, obviously.
	 */
	public static void handle(Socket sock, ToadletContainer container, PageMaker pageMaker) {
		try {
			InputStream is = new BufferedInputStream(sock.getInputStream(), 4096);
			
			LineReadingInputStream lis = new LineReadingInputStream(is);
			
			while(true) {
				
				String firstLine = lis.readLine(32768, 128, false); // ISO-8859-1 or US-ASCII, _not_ UTF-8
				if (firstLine == null) {
					sock.close();
					return;
				} else if (firstLine.equals("")) {
					continue;
				}
				
				boolean logMINOR = Logger.shouldLog(Logger.MINOR, ToadletContextImpl.class);
				
				if(logMINOR)
					Logger.minor(ToadletContextImpl.class, "first line: "+firstLine);
				
				String[] split = firstLine.split(" ");
				
				if(split.length != 3)
					throw new ParseException("Could not parse request line (split.length="+split.length+"): "+firstLine, -1);
				
				if(!split[2].startsWith("HTTP/1."))
					throw new ParseException("Unrecognized protocol "+split[2], -1);
				
				URI uri;
				try {
					uri = URIPreEncoder.encodeURI(split[1]).normalize();
					if(logMINOR) Logger.minor(ToadletContextImpl.class, "URI: "+uri+" path "+uri.getPath()+" host "+uri.getHost()+" frag "+uri.getFragment()+" port "+uri.getPort()+" query "+uri.getQuery()+" scheme "+uri.getScheme());
				} catch (URISyntaxException e) {
					sendURIParseError(sock.getOutputStream(), true, e);
					return;
				}
				
				String method = split[0];
				
				MultiValueTable<String,String> headers = new MultiValueTable<String,String>();
				
				while(true) {
					String line = lis.readLine(32768, 128, false); // ISO-8859 or US-ASCII, not UTF-8
					if (line == null) {
						sock.close();
						return;
					}
					//System.out.println("Length="+line.length()+": "+line);
					if(line.length() == 0) break;
					int index = line.indexOf(':');
					if (index < 0) {
						throw new ParseException("Missing ':' in request header field", -1);
					}
					String before = line.substring(0, index).toLowerCase();
					String after = line.substring(index+1);
					after = after.trim();
					headers.put(before, after);
				}
				
				boolean disconnect = shouldDisconnectAfterHandled(split[2].equals("HTTP/1.0"), headers) || !container.enablePersistentConnections();

				boolean allowPost = container.allowPosts();
				BucketFactory bf = container.getBucketFactory();
				
				ToadletContextImpl ctx = new ToadletContextImpl(sock, headers, bf, pageMaker, container);
				ctx.shouldDisconnect = disconnect;
				
				/*
				 * copy the data into a bucket now,
				 * before we go into the redirect loop
				 */
				
				Bucket data;

				boolean methodIsConfigurable = true;

				String slen = headers.get("content-length");

				if (METHODS_MUST_HAVE_DATA.contains(method)) {
					// <method> must have data
					methodIsConfigurable = false;
					if (slen == null) {
						ctx.shouldDisconnect = true;
						ctx.sendReplyHeaders(400, "Bad Request", null, null, -1);
						return;
					}
				} else if (METHODS_CANNOT_HAVE_DATA.contains(method)) {
					// <method> can not have data
					methodIsConfigurable = false;
					if (slen != null) {
						ctx.shouldDisconnect = true;
						ctx.sendReplyHeaders(400, "Bad Request", null, null, -1);
						return;
					}
				}

				if (slen != null) {
					long len;
					try {
						len = Integer.parseInt(slen);
						if(len < 0) throw new NumberFormatException("content-length less than 0");
					} catch (NumberFormatException e) {
						ctx.shouldDisconnect = true;
						ctx.sendReplyHeaders(400, "Bad Request", null, null, -1);
						return;
					}
					if(allowPost && ((!container.publicGatewayMode()) || ctx.isAllowedFullAccess())) {
						data = bf.makeBucket(len);
						BucketTools.copyFrom(data, is, len);
					} else {
						FileUtil.skipFully(is, len);
						if (method.equals("POST")) {
							ctx.sendMethodNotAllowed("POST", true);
						} else {
							sendError(sock.getOutputStream(), 403, "Forbidden", "Content not allowed in this configuration", true, null);
						}
						ctx.close();
						return;
					}
				} else {
					// we're not doing to use it, but we have to keep
					// the compiler happy
					data = null;
				}

				if (!container.enableExtendedMethodHandling()) {
					if (!METHODS_RESTRICTED_MODE.contains(method)) {
						sendError(sock.getOutputStream(), 403, "Forbidden", "Method not allowed in this configuration", true, null);
						return;
					}
				}

				// Handle it.
				try {
					boolean redirect = true;
					while (redirect) {
						// don't go around the loop unless set explicitly
						redirect = false;
						
						Toadlet t;
						try {
							t = container.findToadlet(uri);
						} catch (PermanentRedirectException e) {
							Toadlet.writePermanentRedirect(ctx, "Found elsewhere", e.newuri.toASCIIString());
							break;
						}
					
						if(t == null) {
							ctx.sendNoToadletError(ctx.shouldDisconnect);
							break;
						}

						// if the Toadlet does not support the method, we don't need to parse the data
						// also due this pre check a 'NoSuchMethodException' should never appear
						if (!(t.findSupportedMethods().contains(method))) {
							ctx.sendMethodNotAllowed(method, ctx.shouldDisconnect);
							break;
						}

						HTTPRequestImpl req = new HTTPRequestImpl(uri, data, ctx, method);
						try {
							String methodName = Toadlet.HANDLE_METHOD_PREFIX + method;
							try {
								Class<? extends Toadlet> c = t.getClass();
								Method m = c.getMethod(methodName, HANDLE_PARAMETERS);
								if (methodIsConfigurable) {
									AllowData anno = m.getAnnotation(AllowData.class);
									if (anno == null) {
										if (data != null) {
											sendError(sock.getOutputStream(), 400, "Bad Request", "Content not allowed", true, null);
											ctx.close();
											return;
										}
									} else if (anno.value()) {
										if (data == null) {
											sendError(sock.getOutputStream(), 400, "Bad Request", "Missing Content", true, null);
											ctx.close();
											return;
										}
									}
								}
								ctx.setActiveToadlet(t);
								Object arglist[] = new Object[] {uri, req, ctx};
								m.invoke(t, arglist);
							} catch (InvocationTargetException ite) {
								throw ite.getCause();
							}
						} catch (RedirectException re) {
							uri = re.newuri;
							redirect = true;
						} finally {
							req.freeParts();
						}
					}
					if(ctx.shouldDisconnect) {
						sock.close();
						return;
					}
				} finally {
					if(data != null) data.free();
				}
			}
			
		} catch (ParseException e) {
			try {
				sendError(sock.getOutputStream(), 400, "Bad Request", l10n("parseErrorWithError", "error", e.getMessage()), true, null);
			} catch (IOException e1) {
				// Ignore
			}
		} catch (TooLongException e) {
			try {
				sendError(sock.getOutputStream(), 400, "Bad Request", l10n("headersLineTooLong"), true, null);
			} catch (IOException e1) {
				// Ignore
			}
		} catch (IOException e) {
			// ignore and return
		} catch (ToadletContextClosedException e) {
			Logger.error(ToadletContextImpl.class, "ToadletContextClosedException while handling connection!");
		} catch (Throwable t) {
			Logger.error(ToadletContextImpl.class, "Caught error: "+t+" handling socket", t);
			try {
				String msg = "<html><head><title>"+NodeL10n.getBase().getString("Toadlet.internalErrorTitle")+
						"</title></head><body><h1>"+NodeL10n.getBase().getString("Toadlet.internalErrorPleaseReport")+"</h1><pre>";
				StringWriter sw = new StringWriter();
				PrintWriter pw = new PrintWriter(sw);
				t.printStackTrace(pw);
				pw.flush();
				msg = msg + sw.toString() + "</pre></body></html>";
				byte[] messageBytes = msg.getBytes("UTF-8");
				sendReplyHeaders(sock.getOutputStream(), 500, "Internal failure", null, "text/html; charset=UTF-8", messageBytes.length, null, true);
				sock.getOutputStream().write(messageBytes);
			} catch (IOException e1) {
				// ignore and return
			}
		}
	}
	
	private void setActiveToadlet(Toadlet t) {
		this.activeToadlet = t;
	}
	
	public Toadlet activeToadlet() {
		return activeToadlet;
	}

	/**
	 * Should the connection be closed after handling this request?
	 * @param isHTTP10 Did the client specify HTTP/1.0?
	 * @param headers Client headers.
	 * @return True if the connection should be closed.
	 */
	private static boolean shouldDisconnectAfterHandled(boolean isHTTP10, MultiValueTable<String,String> headers) {
		String connection = headers.get("connection");
		if(connection != null) {
			if(connection.equalsIgnoreCase("close"))
				return true;
			
			if(connection.equalsIgnoreCase("keep-alive"))
				return false;
		}
		if(isHTTP10 == true)
			return true;
		else
			// HTTP 1.1
			return false;
	}
	
	public void writeData(byte[] data, int offset, int length) throws ToadletContextClosedException, IOException {
		if(closed) throw new ToadletContextClosedException();
		sockOutputStream.write(data, offset, length);
	}
	
	public void writeData(byte[] data) throws ToadletContextClosedException, IOException {
		writeData(data, 0, data.length);
	}
	
	/**
	 * @param data The Bucket which contains the reply data. This function does not free() the Bucket!
	 * 
	 * FIXME: For all references to this function, check whether they free() the Bucket.
	 */
	public void writeData(Bucket data) throws ToadletContextClosedException, IOException {
		if(closed) throw new ToadletContextClosedException();
		BucketTools.copyTo(data, sockOutputStream, Long.MAX_VALUE);
	}
	
	public BucketFactory getBucketFactory() {
		return bf;
	}

	public HTMLNode addFormChild(HTMLNode parentNode, String target, String name) {
		return container.addFormChild(parentNode, target, name);
	}

	public boolean isAllowedFullAccess() {
		return container.isAllowedFullAccess(remoteAddr);
	}

	public boolean doRobots() {
		return container.doRobots();
	}

	public void forceDisconnect() {
		this.shouldDisconnect = true;
	}

	public ToadletContainer getContainer() {
		return container;
	}

	public boolean disableProgressPage() {
		return container.disableProgressPage();
	}
}
