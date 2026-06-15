package com.easysubway.route.application.port.out;

import com.easysubway.route.domain.RouteFeedback;

public interface SaveRouteFeedbackPort {

	RouteFeedback saveRouteFeedback(RouteFeedback feedback);
}
