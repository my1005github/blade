package com.blade.embedd;

import static com.blade.Blade.$;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.annotation.WebFilter;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.webapp.WebAppContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.blade.Blade;
import com.blade.Const;
import com.blade.context.DynamicContext;
import com.blade.context.WebContextListener;
import com.blade.exception.EmbedServerException;
import com.blade.kit.StringKit;
import com.blade.kit.base.Config;
import com.blade.kit.resource.ClassInfo;
import com.blade.kit.resource.ClassReader;
import com.blade.mvc.DispatcherServlet;

public class EmbedJettyServer implements EmbedServer {

	private static final Logger LOGGER = LoggerFactory.getLogger(EmbedJettyServer.class);
	
    private int port = Const.DEFAULT_PORT;
	
	private Server server;
	
	private WebAppContext webAppContext;
	
	private Config config = null;
	
	private ClassReader classReader = null;
    
	public EmbedJettyServer() {
		System.setProperty("org.apache.jasper.compiler.disablejsr199", "true");
		$().loadAppConf("jetty.properties");
		config = $().config();
		classReader = DynamicContext.getClassReader();
		$().enableServer(true);
	}
	
	@Override
	public void startup(int port) throws EmbedServerException {
		this.startup(port, Const.DEFAULT_CONTEXTPATH, null);
	}

	@Override
	public void startup(int port, String contextPath) throws EmbedServerException {
		this.startup(port, contextPath, null);
	}
	
	@Override
	public void setWebRoot(String webRoot) {
		webAppContext.setResourceBase(webRoot);
	}
	
	@Override
	public void startup(int port, String contextPath, String webRoot) throws EmbedServerException {
		this.port = port;
		
		// Setup Threadpool
        QueuedThreadPool threadPool = new QueuedThreadPool();
        
        int minThreads = config.getInt("server.jetty.min-threads", 100);
        int maxThreads = config.getInt("server.jetty.max-threads", 500);
        
        threadPool.setMinThreads(minThreads);
        threadPool.setMaxThreads(maxThreads);
        
		server = new org.eclipse.jetty.server.Server(threadPool);
		
		// 设置在JVM退出时关闭Jetty的钩子。
        server.setStopAtShutdown(true);
        
        webAppContext = new WebAppContext();
        webAppContext.setContextPath(contextPath);
        webAppContext.setResourceBase("");
        
	    int securePort = config.getInt("server.jetty.http.secure-port", 8443);
	    int outputBufferSize = config.getInt("server.jetty.http.output-buffersize", 32768);
	    int requestHeaderSize = config.getInt("server.jetty.http.request-headersize", 8192);
	    int responseHeaderSize = config.getInt("server.jetty.http.response-headersize", 8192);
	    
	    // HTTP Configuration
        HttpConfiguration http_config = new HttpConfiguration();
        http_config.setSecurePort(securePort);
        http_config.setOutputBufferSize(outputBufferSize);
        http_config.setRequestHeaderSize(requestHeaderSize);
        http_config.setResponseHeaderSize(responseHeaderSize);
        http_config.setSendServerVersion(true);
        http_config.setSendDateHeader(false);
        
        long idleTimeout = config.getLong("server.jetty.http.idle-timeout", 30000L);
        
        ServerConnector http = new ServerConnector(server, new HttpConnectionFactory(http_config));
        http.setPort(this.port);
        http.setIdleTimeout(idleTimeout);
        server.addConnector(http);
	    
	    ServletHolder servletHolder = new ServletHolder(DispatcherServlet.class);
	    servletHolder.setAsyncSupported(false);
	    servletHolder.setInitOrder(1);

	    webAppContext.addEventListener(new WebContextListener());
	    webAppContext.addServlet(servletHolder, "/");
	    
	    try {
	    	
		    loadFilters(webAppContext);
		    
		    HandlerList handlers = new HandlerList();
		    handlers.setHandlers(new Handler[] { webAppContext, new DefaultHandler() });
		    server.setHandler(handlers);
	    	server.start();
		    LOGGER.info("Blade Server Listen on 0.0.0.0:{}", this.port);
		} catch (Exception e) {
			throw new EmbedServerException(e);
		}
	}
	
	public List<ClassInfo> loadFilters(WebAppContext webAppContext) throws Exception{
		String filterPkg = Blade.$().applicationConfig().getFilterPkg();
		if (StringKit.isNotBlank(filterPkg)) {
			List<ClassInfo> filters = new ArrayList<ClassInfo>(10);
			Set<ClassInfo> intes = classReader.getClass(filterPkg, false);
			if (null != intes) {
				for (ClassInfo classInfo : intes) {
					if (null != classInfo.getClazz().getInterfaces()
							&& classInfo.getClazz().getInterfaces()[0].equals(Filter.class)) {
						
						WebFilter webFilter = classInfo.getClazz().getAnnotation(WebFilter.class);
						if(null != webFilter){
							String[] pathSpecs = webFilter.value();
							Class<? extends Filter> filterClazz = (Class<? extends Filter>) classInfo.getClazz();
							for(String pathSpec : pathSpecs){
								webAppContext.addFilter(filterClazz, pathSpec, EnumSet.of(DispatcherType.REQUEST));
							}
						}
					}
				}
			}
			return filters;
		}
		return null;
	}
	
    public void shutdown() throws EmbedServerException {
        try {
			server.stop();
		} catch (Exception e) {
			throw new EmbedServerException(e);
		}
    }
    
    @Override
	public void join() throws EmbedServerException {
		try {
			server.join();
		} catch (InterruptedException e) {
			throw new EmbedServerException(e);
		}
	}
    
}