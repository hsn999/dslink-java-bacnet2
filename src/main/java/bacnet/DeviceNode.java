package bacnet;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vertx.java.core.Handler;
import org.vertx.java.core.json.JsonObject;

import bacnet.BacnetConn.CovType;

import com.serotonin.bacnet4j.RemoteDevice;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;
import com.serotonin.bacnet4j.util.PropertyReferences;

public class DeviceNode extends DeviceFolder {
	private static final Logger LOGGER;
	static {
		LOGGER = LoggerFactory.getLogger(DeviceNode.class);
	}
	
	final Node statnode;
	private boolean enabled;
	RemoteDevice device;
	long interval;
	CovType covType;
	
	private final ScheduledThreadPoolExecutor stpe;
	private final ConcurrentMap<ObjectIdentifier, BacnetPoint> subscribedPoints = new ConcurrentHashMap<ObjectIdentifier, BacnetPoint>();
	private ScheduledFuture<?> future = null;
	
	DeviceNode(BacnetConn conn, Node node, RemoteDevice d) {
		super(conn, node);
		this.device = d;
		this.root = this;
		
		if (node.getChild("STATUS") != null) {
			this.statnode = node.getChild("STATUS");
			enabled = new Value("enabled").equals(statnode.getValue());
		} else {
			this.statnode = node.createChild("STATUS").setValueType(ValueType.STRING).setValue(new Value("enabled")).build();
			enabled = true;
		}
		
		if (d == null) {
			statnode.setValue(new Value("disabled"));
			enabled = false;
		}
		
		if (enabled) {
			Action act = new Action(Permission.READ, new Handler<ActionResult>() {
				public void handle(ActionResult event) {
					disable();
				}
			});
			node.createChild("disable").setAction(act).build().setSerializable(false);
		} else {
			Action act = new Action(Permission.READ, new Handler<ActionResult>() {
				public void handle(ActionResult event) {
					enable();
				}
			});
			node.createChild("enable").setAction(act).build().setSerializable(false);
		}
		
		this.interval = node.getAttribute("polling interval").getNumber().longValue();
		this.covType = CovType.NONE;
		try {
			this.covType = CovType.valueOf(node.getAttribute("cov usage").getString());
		} catch (Exception e) {
		}
		
		if (conn.isIP) this.stpe = Objects.createDaemonThreadPool();
		else this.stpe = conn.getDaemonThreadPool();
		
		makeEditAction();

	}
	
	ScheduledThreadPoolExecutor getDaemonThreadPool() {
		return stpe;
	}
	
	private void enable() {
		for (Node child: node.getChildren().values()) {
			if (child.getAction() == null && child != statnode) {
				child.removeConfig("disconnectedTs");
			}
		}
		enabled = true;
		if (future == null) startPolling();
		if (device == null) {
			String mac = node.getAttribute("MAC address").getString();
			int instNum = node.getAttribute("instance number").getNumber().intValue();
			CovType covtype = CovType.NONE;
			try {
				covtype = CovType.valueOf(node.getAttribute("cov usage").getString());
			} catch (Exception e1) {
			}
			int covlife = node.getAttribute("cov lease time (minutes)").getNumber().intValue();
			final RemoteDevice d = conn.getDevice(mac, instNum, interval, covtype, covlife);
			conn.getDeviceProps(d);
			device = d;
		}
		statnode.setValue(new Value("enabled"));
		node.removeChild("enable");
		Action act = new Action(Permission.READ, new Handler<ActionResult>() {
			public void handle(ActionResult event) {
				disable();
			}
		});
		node.createChild("disable").setAction(act).build().setSerializable(false);
		if (device == null) disable();
	}
	
	private void disable() {
		enabled = false;
		stopPolling();
		statnode.setValue(new Value("disabled"));
		node.removeChild("disable");
		Action act = new Action(Permission.READ, new Handler<ActionResult>() {
			public void handle(ActionResult event) {
				enable();
			}
		});
		node.createChild("enable").setAction(act).build().setSerializable(false);
		if (node.getChildren() == null) return;
		for (Node child: node.getChildren().values()) {
			if (child.getAction() == null && child != statnode) {
				String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(Calendar.getInstance().getTime());
				child.setConfig("disconnectedTs", new Value(timeStamp));
			}
		}
	}
	
	@Override
	protected void remove() {
		super.remove();
		if (conn.isIP) stpe.shutdown();
	}
	
	private void makeEditAction() {
		Action act = new Action(Permission.READ, new EditHandler());
		act.addParameter(new Parameter("name", ValueType.STRING, new Value(node.getName())));
		act.addParameter(new Parameter("MAC address", ValueType.STRING, node.getAttribute("MAC address")));
		act.addParameter(new Parameter("instance number", ValueType.NUMBER, node.getAttribute("instance number")));
		double defint = node.getAttribute("polling interval").getNumber().doubleValue()/1000;
	    act.addParameter(new Parameter("polling interval", ValueType.NUMBER, new Value(defint)));
	    act.addParameter(new Parameter("cov usage", ValueType.makeEnum("NONE", "UNCONFIRMED", "CONFIRMED"), node.getAttribute("cov usage")));
	    act.addParameter(new Parameter("cov lease time (minutes)", ValueType.NUMBER, node.getAttribute("cov lease time (minutes)")));
	    Node anode = node.getChild("edit");
	    if (anode == null) node.createChild("edit").setAction(act).build().setSerializable(false);
	    else anode.setAction(act);
	}

	
	private class EditHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String name = event.getParameter("name", ValueType.STRING).getString();
			long interv = (long) (1000*event.getParameter("polling interval", ValueType.NUMBER).getNumber().doubleValue());
			CovType covtype = CovType.NONE;
			try {
				covtype = CovType.valueOf(event.getParameter("cov usage").getString());
			} catch (Exception e1) {
			}
			int covlife =event.getParameter("cov lease time (minutes)", ValueType.NUMBER).getNumber().intValue();
			String mac = event.getParameter("MAC address", ValueType.STRING).getString();
			int instNum = event.getParameter("instance number", ValueType.NUMBER).getNumber().intValue();
			if (!mac.equals(node.getAttribute("MAC address").getString())) {
				final RemoteDevice d = conn.getDevice(mac, instNum, interv, covtype, covlife);
				conn.getDeviceProps(d);
				device = d;
			}
			interval = interv;
			covType = covtype;
//        	try {
//        		mac = d.getAddress().getMacAddress().toIpPortString();
//        	} catch (Exception e) {
//        		mac = Byte.toString(d.getAddress().getMacAddress().getMstpAddress());
//        	}
        	node.setAttribute("MAC address", new Value(mac));
        	node.setAttribute("instance number", new Value(instNum));
	        node.setAttribute("polling interval", new Value(interval));
	        node.setAttribute("cov usage", new Value(covtype.toString()));
	        node.setAttribute("cov lease time (minutes)", new Value(covlife));
	        
	        if (!name.equals(node.getName())) {
				rename(name);
			}
	        
	        stopPolling();
	        startPolling();
	        
	        makeEditAction();
		}
	}
	
	@Override
	protected void duplicate(String name) {
		JsonObject jobj = conn.link.copySerializer.serialize();
		JsonObject parentobj = getParentJson(jobj, node);
		JsonObject nodeobj = parentobj.getObject(node.getName());
		parentobj.putObject(name, nodeobj);
		conn.link.copyDeserializer.deserialize(jobj);
		Node newnode = node.getParent().getChild(name);
		conn.restoreDevice(newnode);
		return;
		
	}
	
	protected JsonObject getParentJson(JsonObject jobj, Node n) {
		return jobj.getObject(conn.node.getName());
	}
	
	//polling
	void addPointSub(BacnetPoint point) {
		if (subscribedPoints.containsKey(point.oid)) return;
		subscribedPoints.put(point.oid, point);
		if (future == null) startPolling();
	}
	
	void removePointSub(BacnetPoint point) {
		subscribedPoints.remove(point.oid);
		if (subscribedPoints.size() == 0) stopPolling();
	}
	
	private void stopPolling() {
		if (future != null) {
			LOGGER.debug("stopping polling for device " + node.getName());
			future.cancel(false);
			future = null;
		}
	}
	
	private void startPolling() {
		if (!enabled || subscribedPoints.size() == 0) return;
		
		LOGGER.debug("starting polling for device " + node.getName());
		future = stpe.scheduleWithFixedDelay(new Runnable() {
			public void run() {
				if (conn.localDevice == null) {
					conn.stop();
					return;
				}
				PropertyReferences refs = new PropertyReferences();
				for (ObjectIdentifier oid: subscribedPoints.keySet()) {
					DeviceFolder.addPropertyReferences(refs, oid);
				}
				LOGGER.debug("polling for device " + node.getName());
		      	getProperties(refs, new ConcurrentHashMap<ObjectIdentifier, BacnetPoint>(subscribedPoints));
			}
		}, 0, interval, TimeUnit.MILLISECONDS);
		
		
	}
	
}
