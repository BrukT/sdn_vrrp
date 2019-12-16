package it.unipi.ing.ce.anaws.vrrm;

import org.restlet.Context;
import org.restlet.Restlet;
import org.restlet.routing.Router;

import net.floodlightcontroller.core.web.ControllerSummaryResource;
import net.floodlightcontroller.core.web.ControllerSwitchesResource;
import net.floodlightcontroller.restserver.RestletRoutable;

public class VRRMWebRoutable implements RestletRoutable {
	
	@Override
	public Restlet getRestlet(Context context) {
		Router router = new Router(context);
		
		router.attach("/controller/summary/json", ControllerSummaryResource.class);
		router.attach("/controller/switches/json", ControllerSwitchesResource.class);
		router.attach("/config", VirtualRouterRedundancyManagerResources.class);
		return router;
	}

	@Override
	public String basePath() {
		return "/vrrm";
	}

}
