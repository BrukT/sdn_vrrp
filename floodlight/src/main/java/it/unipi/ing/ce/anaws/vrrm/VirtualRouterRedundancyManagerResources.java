package it.unipi.ing.ce.anaws.vrrm;

import java.io.IOException;
import java.util.Map;

import org.restlet.resource.Get;
import org.restlet.resource.Post;
import org.restlet.resource.ServerResource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class VirtualRouterRedundancyManagerResources extends ServerResource {
	@Get("json")
	public Map<String, Object> getHandler(){
		IVirtualRouterRedundancyManagerREST vrrm = (IVirtualRouterRedundancyManagerREST)getContext().getAttributes().get(IVirtualRouterRedundancyManagerREST.class.getCanonicalName());
		return vrrm.getVRRMInfo();
	}
	
	@Post
	public String postHandler(String fmJson) {
		if (fmJson == null) {
			return new String("[JSON] Malformed request");
		}
		
		IVirtualRouterRedundancyManagerREST vrrm = (IVirtualRouterRedundancyManagerREST)getContext().getAttributes().get(IVirtualRouterRedundancyManagerREST.class.getCanonicalName());
		ObjectMapper mapper = new ObjectMapper();
		
		try {
			JsonNode root = mapper.readTree(fmJson);
			JsonNode virtualRouter = root.get("virtual_router_ip");
			JsonNode primaryRouter = root.get("primary_router_ip");
			JsonNode preemptionMode = root.get("preemption_mode");
			
			if (virtualRouter == null && primaryRouter == null && preemptionMode == null)
				return new String("[KEY] Malformed request");
			
			if (virtualRouter != null)
				vrrm.setVirtualRouterIP(virtualRouter.asText());
			
			if (primaryRouter != null)
				vrrm.setPrimaryRouterIP(primaryRouter.asText());
			
			if (preemptionMode != null)
				vrrm.setPreemptionMode(preemptionMode.asBoolean());
			
		} catch (IOException e) {
			e.printStackTrace();
		} catch (IllegalArgumentException e) {
			return new String("[VALUE] Malformed request");
		}
		
		return new String("OK");
	}
}
