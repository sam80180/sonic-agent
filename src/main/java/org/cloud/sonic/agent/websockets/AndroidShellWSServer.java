package org.cloud.sonic.agent.websockets;

import com.alibaba.fastjson.JSONObject;
import com.android.ddmlib.IDevice;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import com.pty4j.unix.PtyHelpers;
import jakarta.websocket.CloseReason;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;
import org.cloud.sonic.agent.bridge.android.AndroidDeviceBridgeTool;
import org.cloud.sonic.agent.bridge.android.AndroidDeviceThreadPool;
import org.cloud.sonic.agent.common.config.WsEndpointConfigure;
import org.cloud.sonic.agent.common.maps.WebSocketSessionMap;
import org.cloud.sonic.agent.tools.BytesTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@ServerEndpoint(value = "/websockets/android/shell/{key}/{udId}/{token}", configurator = WsEndpointConfigure.class)
public class AndroidShellWSServer implements IAndroidWSServer {
    @Value("${sonic.agent.key}")
    private String key;
    
    private Map<Session, MyTty> ttys = new ConcurrentHashMap<Session, MyTty>();

    private final class MyTty {
		private PtyProcess process;
		private BufferedWriter stdin;
		private BufferedReader stdout, stderr;
		private Future<?> tStdout, tStderr;
		private Session session;
		
		public MyTty(Session session, final String u) throws Exception {
			final String B1N = AndroidDeviceBridgeTool.getADBPathFromSystemEnv();
			if (B1N==null) {
				throw new Exception("Unable to find 'adb' executable!");
			}
			this.session = session;
			final List<NameValuePair> params = URLEncodedUtils.parse(session.getRequestURI(), Charset.forName("UTF-8"));
			int rr = 0, cc = 0;
			for (NameValuePair nv : params) {
				final String paramName = nv.getName();
				final String paramValue = nv.getValue();
				try {
					if (paramName.equals("rows")) {
						rr = Integer.parseInt(paramValue);
					} else if (paramName.equals("cols")) {
						cc = Integer.parseInt(paramValue);
					}
				} catch (Exception e) {}
			}
			final String cd[] = {B1N, "-s", u, "shell"};
			final HashMap<String, String> ev = new HashMap<>(System.getenv());
			ev.put("TERM", "xterm");
			PtyProcessBuilder ppb = new PtyProcessBuilder().setCommand(cd).setEnvironment(ev);
			if (rr>0) { ppb.setInitialRows(rr); }
			if (cc>0) { ppb.setInitialColumns(cc); }
			this.process = ppb.start();
			this.process.onExit().thenRun(() -> {
				log.info("ADB shell for {} stopped.", u);
				try {
					session.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			});
			this.stdin = new BufferedWriter(new OutputStreamWriter(this.process.getOutputStream()));
			this.stdout = new BufferedReader(new InputStreamReader(this.process.getInputStream()));
			this.stderr = new BufferedReader(new InputStreamReader(this.process.getErrorStream()));
			this.tStdout = AndroidDeviceThreadPool.cachedThreadPool.submit(() -> {
				this.printReader(this.stdout);
			});
			this.tStderr = AndroidDeviceThreadPool.cachedThreadPool.submit(() -> {
				this.printReader(this.stderr);
			});
		}
		
		public void sendCommand(String s) throws IOException {
			this.stdin.write(s);
			this.stdin.flush();
		}
		
		public void resize(int r, int c) {
			this.process.setWinSize(new WinSize(c, r));
		}
		
		public void close() {
			try {
	        	if (this.tStdout!=null) { this.tStdout.cancel(true); }
        	} catch (Exception aa) {}
			try {
	        	if (this.tStderr!=null) { this.tStderr.cancel(true); }
        	} catch (Exception aa) {}
			try {
	        	if (this.stdin!=null) { this.stdin.close(); }
        	} catch (Exception aa) {}
			try {
	        	if (this.stdout!=null) { this.stdout.close(); }
        	} catch (Exception aa) {}
			try {
	        	if (this.stderr!=null) { this.stderr.close(); }
        	} catch (Exception aa) {}
			try {
				if (this.process!=null) { PtyHelpers.getInstance().kill((int)this.process.pid(), PtyHelpers.SIGKILL); }
        	} catch (Exception aa) {}
		}
		
		private void printReader(BufferedReader bfr) {
	        try {
	            int n;
	            char[] c = new char[10240];
	            while ((n=bfr.read(c, 0, c.length))!=-1) {
	                final StringBuilder sbr = new StringBuilder(n);
	                sbr.append(c, 0, n);
	                BytesTool.sendText(this.session, sbr.toString());
	            }
	        } catch (Exception aa) {
	            log.warn(aa.getMessage());
	            aa.printStackTrace();
	        }
	    }
	}
    
    @OnOpen
    public void onOpen(Session s, @PathParam("key") String sk, @PathParam("udId") String u, @PathParam("token") String n) throws Exception {
		if (sk.length()<=0 || !sk.equals(this.key) || n.length()<=0) {
	        log.warn("Unauthorized access to {} !", s.getRequestURI());
	        s.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, HttpStatus.UNAUTHORIZED.toString()));
	        return;
	    }
		final IDevice id = AndroidDeviceBridgeTool.getIDeviceByUdId(u);
		if (id==null) {
            log.warn("Target device is not connecting, please check the connection.");
            s.close(new CloseReason(CloseReason.CloseCodes.UNEXPECTED_CONDITION, HttpStatus.INTERNAL_SERVER_ERROR.toString()));
            return;
        }
        s.getUserProperties().put("udId", u);
        s.getUserProperties().put("id", String.format("%s-%s", this.getClass().getSimpleName(), u));
        WebSocketSessionMap.addSession(s);
        this.saveUdIdMapAndSet(s, id);
        this.startService(udIdMap.get(s), s);
    }

    private static final Pattern ansiResizePattern = Pattern.compile("^\u001B\\[8;(\\d+);(\\d+)t$");
    
    private void startService(IDevice d, Session s) {
        try {
            Thread.sleep(2500);
        } catch (InterruptedException e) {
            log.info(e.getMessage());
        }
		try {
			MyTty m = new MyTty(s, d.getSerialNumber());
			this.ttys.put(s, m);
		} catch (Exception aa) {
			aa.printStackTrace();
		}
    }
    
    @OnMessage
    public void onMessage(String q, Session g) {
		Matcher c = ansiResizePattern.matcher(q);
		try {
			final MyTty m = this.ttys.get(g);
			if (m==null) { return; }
			if (c.find()) {
				int cc = Integer.parseInt(c.group(2));
				int rr = Integer.parseInt(c.group(1));
				m.resize(rr, cc);
				return;
			}
			m.sendCommand(q);
		} catch (IOException aa) {
			log.warn(aa.getMessage());
			aa.printStackTrace();
		}
	}

    @OnClose
    public void onClose(Session c) {
        this.exit(c);
    }

    @OnError
    public void onError(Session s, Throwable t) {
		final String m = t.getMessage();
        log.error(m);
        t.printStackTrace();
        final JSONObject j = new JSONObject();
        j.put("msg", "error");
        j.put("detail", m);
        BytesTool.sendText(s, j.toJSONString());
    }

    private void exit(Session a) {
        synchronized (a) {
        	final MyTty m = this.ttys.get(a);
        	if (m!=null) {
        		m.close();
        		WebSocketSessionMap.removeSession(a);
                this.removeUdIdMapAndSet(a);
                try {
                    a.close();
                } catch (IOException aa) {
                    aa.printStackTrace();
                }
                log.info("{} : quit.", a.getUserProperties().get("id").toString());
                this.ttys.remove(a);
        	}
        }
    }
}
