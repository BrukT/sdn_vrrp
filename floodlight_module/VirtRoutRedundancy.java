package net.floodlightcontroller.virtRoutRedundancy;

import java.util.ArrayList;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

import org.projectfloodlight.openflow.protocol.OFFlowAdd;
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
import org.projectfloodlight.openflow.types.U64;
import org.projectfloodlight.openflow.util.HexString;
import org.restlet.Context;
import org.restlet.Restlet;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.IListener.Command;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.loadbalancer2.LoadBalancer2;
import net.floodlightcontroller.packet.ARP;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.ICMP;
import net.floodlightcontroller.packet.IPacket;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.restserver.RestletRoutable;
import net.floodlightcontroller.util.FlowModUtils;

import java.util.Date; 

//net.floodlightcontroller.virtualhost.VirtualHost

public class VirtRoutRedundancy implements IFloodlightModule, IOFMessageListener {
protected IFloodlightProviderService floodlightProvider; // Reference to the provider
	
	// IP and MAC address for our logical load balancer
	private static IPv4Address VIRTUAL_ROUTER_IP = IPv4Address.of("10.0.1.10");
	private static MacAddress VIRTUAL_ROUTER_MAC = MacAddress.of("00:00:00:00:00:10");
	
	private static IPv4Address PRIMARY_ROUTER_IP = IPv4Address.of("10.0.1.1");
	private static MacAddress PRIMARY_ROUTER_MAC = MacAddress.of("00:00:00:00:00:01");
	
	private static IPv4Address SECONDARY_ROUTER_IP = IPv4Address.of("10.0.1.2");
	private static MacAddress SECONDARY_ROUTER_MAC = MacAddress.of("00:00:00:00:00:02");
	
	private final static IPv4Address ADVERT_DESTN = IPv4Address.of("10.0.1.255");	
	private final static MacAddress SWITCH_MAC =  MacAddress.of("00:00:00:00:00:21");
	private static MacAddress ROUTER_MAC;
	private static IPv4Address ROUTER_IP;
	private static Date date = new Date();
	private long seen_time = date.getTime();
	private long waiting_period = 3;
	

	@Override
	public String getName() {
		return VirtRoutRedundancy.class.getSimpleName();
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
		// TODO Auto-generated method stub
		floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
	
	}

	@Override
	public void startUp(FloodlightModuleContext context) throws FloodlightModuleException {
		floodlightProvider.addOFMessageListener(OFType.PACKET_IN, this);
		//make arp request
	}

	@Override
	public net.floodlightcontroller.core.IListener.Command receive(IOFSwitch sw, OFMessage msg,
			FloodlightContext cntx) {
			
			Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
			
			IPacket pkt = eth.getPayload();

			// Print the source MAC address
			Long sourceMACHash = Ethernet.toLong(eth.getSourceMACAddress().getBytes());
			System.out.printf("MAC Address: {%s} seen on switch: {%s}\n", HexString.toHexString(sourceMACHash), sw.getId());
			
			// Cast to Packet-In
			OFPacketIn pi = (OFPacketIn) msg;

	        // Dissect Packet included in Packet-In
			if (eth.isBroadcast() || eth.isMulticast()) {
				if (pkt instanceof ARP) {					
					System.out.printf("Processing ARP request RoutRedundacy\n");					
					ARP arpRequest = (ARP) eth.getPayload();						
					if((arpRequest.getTargetProtocolAddress().compareTo(VIRTUAL_ROUTER_IP) == 0)){					
						// Process ARP request
						handleARPRequest(sw, pi, cntx);					
						// Interrupt the chain
						return Command.STOP;
					}
					else if(eth.getSourceMACAddress().compareTo(PRIMARY_ROUTER_MAC) == 0){					
						// Process ARP request
						handleARPRequestAD(sw, pi, cntx);					
						// Interrupt the chain
						return Command.STOP;
					}
				}
			} 
			else {
			  if (pkt instanceof IPv4) {										
				IPv4 ip_pkt = (IPv4) pkt;
				System.out.printf("Processing IPv4 packet in RoutRedudancy\n");	
				if((ip_pkt.getDestinationAddress().compareTo(ADVERT_DESTN) == 0)) {
					handleAdvert(sw, pi, cntx);						
				// Interrupt the chain
					return Command.STOP;
				}
			} 
			}
			// Interrupt the chain
			return Command.CONTINUE;

	}

	private void handleARPRequestAD(IOFSwitch sw, OFPacketIn pi, FloodlightContext cntx) {
		// Double check that the payload is ARP
		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);		
		if (! (eth.getPayload() instanceof ARP))
			return;	
		
		handleAdvert(sw, pi, cntx);	
	}

	private void handleARPRequest(IOFSwitch sw, OFPacketIn pi,	FloodlightContext cntx) {
		// Double check that the payload is ARP
		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);		
		if (! (eth.getPayload() instanceof ARP))
			return;	
		
		// Cast the ARP request
				ARP arpRequest = (ARP) eth.getPayload();
		
		//if address is not resolved yet
		//if(ROUTER_MAC == null) 
			//return;
		// Generate ARP reply
		IPacket arpReply = new Ethernet()
			.setSourceMACAddress(PRIMARY_ROUTER_MAC)
			.setDestinationMACAddress(eth.getSourceMACAddress())
			.setEtherType(EthType.ARP)
			.setPriorityCode(eth.getPriorityCode())
			.setPayload(
				new ARP()
				.setHardwareType(ARP.HW_TYPE_ETHERNET)
				.setProtocolType(ARP.PROTO_TYPE_IP)
				.setHardwareAddressLength((byte) 6)
				.setProtocolAddressLength((byte) 4)
				.setOpCode(ARP.OP_REPLY)
				.setSenderHardwareAddress(PRIMARY_ROUTER_MAC) // Set my MAC address
				.setSenderProtocolAddress(VIRTUAL_ROUTER_IP) // Set my IP address
				.setTargetHardwareAddress(arpRequest.getSenderHardwareAddress())
				.setTargetProtocolAddress(arpRequest.getSenderProtocolAddress()));
		
		// Create the Packet-Out and set basic data for it (buffer id and in port)
		OFPacketOut.Builder pob = sw.getOFFactory().buildPacketOut();
		pob.setBufferId(OFBufferId.NO_BUFFER);
		pob.setInPort(OFPort.ANY);
		
		// Create action -> send the packet back from the source port
		OFActionOutput.Builder actionBuilder = sw.getOFFactory().actions().buildOutput();
		OFPort inPort =  pi.getMatch().get(MatchField.IN_PORT);
        actionBuilder.setPort(inPort);  
		
		// Assign the action
		pob.setActions(Collections.singletonList((OFAction) actionBuilder.build()));
		
		// Set the ARP reply as packet data 
		byte[] packetData = arpReply.serialize();
		pob.setData(packetData);
		
		System.out.printf("Sending out ARP reply in Rout Redundancy to the Host\n");
		
		sw.write(pob.build());
		
	}

	private void handleAdvert(IOFSwitch sw, OFPacketIn pi, FloodlightContext cntx) {

		// Double check that the payload is IPv4
		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
		if (!(eth.getPayload() instanceof IPv4))
			return;
		System.out.println("MAC ADVERT: processing the advertisement");
		if(ROUTER_MAC == null ) {
			System.out.println("ADVERT: Assigning the first MAC :" + eth.getSourceMACAddress());
			ROUTER_MAC = eth.getSourceMACAddress();
			seen_time = date.getTime();
			// Cast the IP packet
			IPv4 ipv4 = (IPv4) eth.getPayload();
			ROUTER_IP = ipv4.getSourceAddress();
		}else {
			System.out.println("ADVERT: after second time MAC advert");
			if(eth.getSourceMACAddress().compareTo(ROUTER_MAC) == 0) {
				seen_time = date.getTime();
			}
			else if(eth.getSourceMACAddress().compareTo(ROUTER_MAC) != 0) {
				long temp = date.getTime();
				if((temp - seen_time) > waiting_period) {
					System.out.println("changing mac address from " + ROUTER_MAC + " to " + eth.getSourceMACAddress());
					ROUTER_MAC = eth.getSourceMACAddress();
					
				}
			}
				
		}
	}

}
