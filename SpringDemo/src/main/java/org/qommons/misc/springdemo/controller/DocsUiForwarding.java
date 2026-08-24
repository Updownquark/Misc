package org.qommons.misc.springdemo.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DocsUiForwarding {
	@GetMapping("/docs-ui")
	public String forwardToSwagger() {
		return "forward:/swagger-ui/index.html";
	}
}
