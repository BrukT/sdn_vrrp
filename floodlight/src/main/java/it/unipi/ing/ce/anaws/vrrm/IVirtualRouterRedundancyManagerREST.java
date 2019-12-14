package it.unipi.ce.anaws.vrrm;

import java.util.Map;

import net.floodlightcontroller.core.module.IFloodlightService;

public interface IVirtualRouterRedundancyManagerREST extends IFloodlightService {
	public Map<String, Object> getVRRMInfo();
	public void setVirtualRouterIP(String newValue);
	public void setPrimaryRouterIP(String newValue);
	public void setPreemptionMode(boolean mode);
}
