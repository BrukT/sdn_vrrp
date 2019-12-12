package net.floodlightcontroller.unipi.vrrm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Map;

import org.projectfloodlight.openflow.protocol.OFFlowAdd;
import org.projectfloodlight.openflow.protocol.OFFlowMod;
import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFPacketIn;
import org.projectfloodlight.openflow.protocol.OFPacketOut;
import org.projectfloodlight.openflow.protocol.OFType;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.action.OFActionOutput;
import org.projectfloodlight.openflow.protocol.action.OFActionSetField;
import org.projectfloodlight.openflow.protocol.action.OFActions;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.protocol.oxm.OFOxms;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.IpProtocol;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.OFBufferId;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.TransportPort;
import org.projectfloodlight.openflow.types.U64;

import com.kenai.jaffl.Address;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.IListener.Command;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.packet.ARP;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.ICMP;
import net.floodlightcontroller.packet.IPacket;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.UDP;
import net.floodlightcontroller.util.FlowModUtils;

public class VirtualRouterRedundancyManager implements IOFMessageListener, IFloodlightModule {
	protected IFloodlightProviderService floodlightProvider;
	// VIRTUAL ROUTER
	private final IPv4Address VIRTUAL_ROUTER_IP = IPv4Address.of("10.0.1.10");
	
	private IPv4Address MASTER_ROUTER_IP;
	private MacAddress MASTER_ROUTER_MAC;
	/*
	 * Last time that the controller received an ADV from the Master
	 */
	private long LAST_MASTER_ADV;
	/* 
	 * Time interval for Controller to declare Master down. 
	 * 1 because it is supposed the master to send an ADV every second.
	 */
	private final int MASTER_DOWN_INTERVAL = 1; 
	
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
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		Collection<Class<? extends IFloodlightService>> l = new ArrayList<Class<? extends IFloodlightService>>();
		l.add(IFloodlightProviderService.class);
		return l;
	}

	@Override
	public void init(FloodlightModuleContext context) throws FloodlightModuleException {
		floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
	}

	@Override
	public void startUp(FloodlightModuleContext context) throws FloodlightModuleException {
		floodlightProvider.addOFMessageListener(OFType.PACKET_IN,  this);
		System.out.println("[I] Startup VRRM");
	}

	@Override
	public Command receive(IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
		IPacket pkt = eth.getPayload();
		
		//System.out.printf("==== Received packet-in ====\n");
		
		
		//System.out.printf("[MAC] source address: %s\n", eth.getSourceMACAddress());
		//System.out.printf("[MAC] dest address: %s\n", eth.getDestinationMACAddress());
		
		
		if (eth.isBroadcast() || eth.isMulticast()) {
			if (pkt instanceof ARP) {
				/*
				 * ARP request from hosts
				 */
				handleARPRequest(sw, msg, cntx);
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
						handleAdvertisement(sw, eth);
						return Command.STOP;
					}
				}
			}	
		}
		return Command.CONTINUE;
	}
	
	
	public void handleARPRequest(IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
		if (! (eth.getPayload() instanceof ARP))
			return;
		
		ARP arpRequest = (ARP) eth.getPayload();
		System.out.println("[I] Handling ARP request for " + arpRequest.getTargetProtocolAddress());
		if (arpRequest.getTargetProtocolAddress().compareTo(VIRTUAL_ROUTER_IP) != 0)
			return;
		
		OFPacketIn pi = (OFPacketIn)msg;
		
		// Replay on behalf of MASTER_ROUTER
		System.out.printf("[DBG] MAC Master: %s - IP Master: %s\n", MASTER_ROUTER_MAC, MASTER_ROUTER_IP);
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
		
		sendPacketOut(sw, pi.getMatch().get(MatchField.IN_PORT), arpReply);
	}
	
	public void sendGratuitousARPRequest(IOFSwitch sw) {
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
						.setTargetProtocolAddress(IPv4Address.of("10.0.1.255"))
				);
		
		sendPacketOut(sw, OFPort.FLOOD, arpRequest);
	}
	
	private void handleAdvertisement(IOFSwitch sw, Ethernet eth) {
		if ( !(eth.getPayload() instanceof IPv4) )
			return;
		IPv4 pkt = (IPv4) eth.getPayload();
		/*
		 * You are here because the controller received a broadcast UDP packet on port 2020
		 */
		long timestamp = (new Date()).getTime(); 
		//System.out.printf("[%d][P] Handling advertisement from %s\n", timestamp, pkt.getSourceAddress());
		//System.out.printf("[P] Master seen %d ms ago\n", (timestamp - LAST_MASTER_ADV));
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
			sendGratuitousARPRequest(sw);
			return;
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
		
		System.out.printf("sending packet-out on port {%s}\n", port.toString());
				
		// Send packet
		sw.write(pob.build());
	}

}
