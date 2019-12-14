package it.unipi.ce.anaws.vrrm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFPacketIn;
import org.projectfloodlight.openflow.protocol.OFPacketOut;
import org.projectfloodlight.openflow.protocol.OFType;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.action.OFActionOutput;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.OFBufferId;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.TransportPort;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.packet.ARP;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPacket;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.UDP;
import net.floodlightcontroller.restserver.IRestApiService;

public class VirtualRouterRedundancyManager implements IOFMessageListener, IFloodlightModule, IVirtualRouterRedundancyManagerREST {
	protected IFloodlightProviderService floodlightProvider;
	protected IRestApiService restApiService;
	// Virtual router
	private IPv4Address VIRTUAL_ROUTER_IP = IPv4Address.of("10.0.1.10");
	/*
	 * Primary Router is the one that have the max priority and when active
	 * it has to be always the Mater router.
	 * It has meaning in the preemption mode.
	 */
	private boolean preemptionMode = false;
	private IPv4Address PRIMARY_ROUTER_IP = IPv4Address.of("10.0.1.1");
	
	private IPv4Address MASTER_ROUTER_IP;
	private MacAddress MASTER_ROUTER_MAC;
	/*
	 * Last time that the controller received an ADV from the Master
	 */
	private long LAST_MASTER_ADV;
	/* 
	 * Time interval for Controller to declare Master down. 
	 * 1 because it is supposed the Master to send an ADV every second.
	 */
	private final int MASTER_DOWN_INTERVAL = 1;
	/* Magic number for Advertisement Packet */
	private final byte[] MAGIC = {'A', 'D', 'V'};
	
	@Override
	public String getName() {
		return VirtualRouterRedundancyManager.class.getSimpleName();
	}

	@Override
	public boolean isCallbackOrderingPrereq(OFType type, String name) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean isCallbackOrderingPostreq(OFType type, String name) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		Collection<Class<? extends IFloodlightService>> l = new ArrayList<Class<? extends IFloodlightService>>();
		l.add(IVirtualRouterRedundancyManagerREST.class);
		return null;
	}

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		Map<Class<? extends IFloodlightService>, IFloodlightService> m = new HashMap<Class<? extends IFloodlightService>, IFloodlightService>();
		m.put(IVirtualRouterRedundancyManagerREST.class, this);
		return m;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		Collection<Class<? extends IFloodlightService>> l = new ArrayList<Class<? extends IFloodlightService>>();
		l.add(IFloodlightProviderService.class);
		l.add(IRestApiService.class);
		return l;
	}

	@Override
	public void init(FloodlightModuleContext context) throws FloodlightModuleException {
		floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
		restApiService = context.getServiceImpl(IRestApiService.class);
	}

	@Override
	public void startUp(FloodlightModuleContext context) throws FloodlightModuleException {
		floodlightProvider.addOFMessageListener(OFType.PACKET_IN,  this);
		restApiService.addRestletRoutable(new VRRMWebRoutable());
	}

	@Override
	public Command receive(IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
		IPacket pkt = eth.getPayload();
		
		if (eth.isBroadcast() || eth.isMulticast()) {
			if (pkt instanceof ARP) {
				if (handleARPRequest(sw, msg, cntx))
					/* 
					 * ARP request actually consumed:
					 * It was for sure a request for 
					 * the virtual router and the Master is set
					 */
					return Command.STOP;				
			}
			
			if (pkt instanceof IPv4) {
				IPv4 ipv4 = (IPv4) pkt;
				if (ipv4.getPayload() instanceof UDP) {
					UDP udp = (UDP) ipv4.getPayload();
					/*
					 * Check if it is an ADV packet
					 */
					if (udp.getDestinationPort().compareTo(TransportPort.of(2020)) == 0){
						byte[] payload = udp.getPayload().serialize();
						if (Arrays.equals(payload, MAGIC)) {
							handleAdvertisement(sw, eth);
							return Command.STOP;
						}
					}
				}
			}
		}
	
		return Command.CONTINUE;
	}
	
	
	public boolean handleARPRequest(IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
		if (! (eth.getPayload() instanceof ARP))
			return false;
		
		ARP arpRequest = (ARP) eth.getPayload();
		
		System.out.print("[I] Handling ARP request for " + arpRequest.getTargetProtocolAddress() + ": ");
		if (arpRequest.getTargetProtocolAddress().compareTo(VIRTUAL_ROUTER_IP) != 0) {
			System.out.println("don't care");
			return false;
		}
		System.out.println("ok");
		
		if (MASTER_ROUTER_IP == null)
			return false;
		
		/*
		 * Reply ARP requests only for the Virtual Router 
		 * and the Master has been elected
		 */
		
		OFPacketIn pi = (OFPacketIn)msg;
		
		// Replay on behalf of MASTER_ROUTER
		IPacket arpReply = new Ethernet()
				.setSourceMACAddress(MASTER_ROUTER_MAC)
				.setDestinationMACAddress(eth.getSourceMACAddress())
				.setEtherType(EthType.ARP)
				.setPriorityCode(eth.getPriorityCode())
				.setPayload(
						new ARP()
						.setHardwareType(ARP.HW_TYPE_ETHERNET)
						.setProtocolType(ARP.PROTO_TYPE_IP)
						.setHardwareAddressLength((byte)6)
						.setProtocolAddressLength((byte)4)
						.setOpCode(ARP.OP_REPLY)
						.setSenderHardwareAddress(MASTER_ROUTER_MAC)
						.setSenderProtocolAddress(VIRTUAL_ROUTER_IP)
						.setTargetHardwareAddress(arpRequest.getSenderHardwareAddress())
						.setTargetProtocolAddress(arpRequest.getSenderProtocolAddress())
				);
		
		System.out.printf("[ARP] %s: %s - %s\n", VIRTUAL_ROUTER_IP, MASTER_ROUTER_MAC, MASTER_ROUTER_IP);
		sendPacketOut(sw, pi.getMatch().get(MatchField.IN_PORT), arpReply);
		return true;
	}
	
	public void sendGratuitousARPReply(IOFSwitch sw) {
		// Replay on behalf of MASTER_ROUTER
		IPacket arpRequest = new Ethernet()
				.setSourceMACAddress(MASTER_ROUTER_MAC)
				.setDestinationMACAddress(MacAddress.BROADCAST)
				.setEtherType(EthType.ARP)
				.setPriorityCode((byte)0)
				.setPayload(
						new ARP()
						.setHardwareType(ARP.HW_TYPE_ETHERNET)
						.setProtocolType(ARP.PROTO_TYPE_IP)
						.setHardwareAddressLength((byte)6)
						.setProtocolAddressLength((byte)4)
						.setOpCode(ARP.OP_REPLY)
						.setSenderHardwareAddress(MASTER_ROUTER_MAC)
						.setSenderProtocolAddress(VIRTUAL_ROUTER_IP)
						.setTargetHardwareAddress(MacAddress.BROADCAST)
						.setTargetProtocolAddress(VIRTUAL_ROUTER_IP)
				);
		
		System.out.printf("[ARP] Gratuitous reply for %s: %s - %s\n", VIRTUAL_ROUTER_IP, MASTER_ROUTER_MAC, MASTER_ROUTER_IP);
		sendPacketOut(sw, OFPort.FLOOD, arpRequest);
	}
	
	private void handleAdvertisement(IOFSwitch sw, Ethernet eth) {
		if ( !(eth.getPayload() instanceof IPv4) )
			return;
		IPv4 pkt = (IPv4) eth.getPayload();
		/*
		 * You are here because the controller received an Advertisement-like packet
		 */
		long timestamp = (new Date()).getTime(); 
		if (MASTER_ROUTER_IP == null) {
			/*
			 * The Master has not been initialized yet
			 */
			System.out.println("[P] init: Assigning " + pkt.getSourceAddress() + " to the Master");
			MASTER_ROUTER_IP = pkt.getSourceAddress();
			MASTER_ROUTER_MAC = eth.getSourceMACAddress();
			LAST_MASTER_ADV = timestamp;
			return;
		}
		
		if (pkt.getSourceAddress().compareTo(MASTER_ROUTER_IP) == 0) {
			/*
			 * ADV received from the Master
			 */
			LAST_MASTER_ADV = timestamp;
			return;
		}
		
		if ( (timestamp - LAST_MASTER_ADV)/1000 > MASTER_DOWN_INTERVAL ) {
			/*
			 * You are not receiving ADVs from the Master anymore.
			 * You are receiving this message from the Backup router
			 */
			System.out.println("[P] Backup " + pkt.getSourceAddress() + " became the Master");
			MASTER_ROUTER_IP = pkt.getSourceAddress();
			MASTER_ROUTER_MAC = eth.getSourceMACAddress();
			LAST_MASTER_ADV = timestamp;
			sendGratuitousARPReply(sw);
			return;
		}
		
		/* 
		 * Preemption:
		 * if the controller receive an ADV from the router with higher priority
		 * but actually it is the Backup router, it will become the Master.
		 */
		if (preemptionMode) {
			if (pkt.getSourceAddress().compareTo(PRIMARY_ROUTER_IP) == 0 && MASTER_ROUTER_IP.compareTo(PRIMARY_ROUTER_IP) != 0) {
				System.out.printf("[P] Preemption: %s became the Master\n", PRIMARY_ROUTER_IP);
				MASTER_ROUTER_IP = pkt.getSourceAddress();
				MASTER_ROUTER_MAC = eth.getSourceMACAddress();
				LAST_MASTER_ADV = timestamp;
				sendGratuitousARPReply(sw);
				return;
			}
		}
		
	}
	
	private void sendPacketOut(IOFSwitch sw, OFPort port, IPacket reply) {
		// Initialize packet-out
		OFPacketOut.Builder pob = sw.getOFFactory().buildPacketOut();
		pob.setBufferId(OFBufferId.NO_BUFFER);
		pob.setInPort(OFPort.ANY);
				
		// Set the output function: the packet must be send through the input port
		OFActionOutput.Builder actionBuilder = sw.getOFFactory().actions().buildOutput();
		actionBuilder.setPort(port);
		pob.setActions(Collections.singletonList((OFAction)actionBuilder.build()));
				
		// Set the ARP replay as packet data
		byte[] packetData = reply.serialize();
		pob.setData(packetData);
		
		System.out.printf("[ARP] Sending packet-out on port {%s}\n", port.toString());
				
		// Send packet
		sw.write(pob.build());
	}

	@Override
	public Map<String, Object> getVRRMInfo() {
		Map<String, Object> info = new HashMap<String, Object>();
		info.put("virtual_router_ip", VIRTUAL_ROUTER_IP.toString());
		if (PRIMARY_ROUTER_IP == null)
			info.put("primary_router_ip", null);
		else
			info.put("primary_router_ip", PRIMARY_ROUTER_IP.toString());
		info.put("preemption_mode", Boolean.toString(preemptionMode));
		return info;
	}

	@Override
	public void setVirtualRouterIP(String newValue) throws IllegalArgumentException {
		VIRTUAL_ROUTER_IP = IPv4Address.of(newValue);
	}

	@Override
	public void setPrimaryRouterIP(String newValue) throws IllegalArgumentException {
		PRIMARY_ROUTER_IP = IPv4Address.of(newValue);
	}

	@Override
	public void setPreemptionMode(boolean newValue) {
		preemptionMode = newValue;
	}

}
