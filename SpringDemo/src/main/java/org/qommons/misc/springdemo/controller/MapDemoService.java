package org.qommons.misc.springdemo.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/mapDemo")
public class MapDemoService {
	private double theLat0 = 32;
	private double theLon0 = -106;
	private double theAlt0 = 2;
	private double theLat1 = 32.1;
	private double theLon1 = -106.1;
	private double theAlt1 = 2;

	@GetMapping("/values")
	public ApiMapDemoValues get() {
		return new ApiMapDemoValues(theLat0, theLon0, theAlt0, theLat1, theLon1, theAlt1);
	}

	@PutMapping("/lat/{index}/{value}")
	public void setLat(@PathVariable int index, @PathVariable double value) {
		switch (index) {
		case 0:
			theLat0 = value;
			break;
		case 1:
			theLat1 = value;
			break;
		default:
			throw new IllegalArgumentException("Illegal index: 0 or 1 allowed");
		}
	}

	@PutMapping("/lon/{index}/{value}")
	public void setLon(@PathVariable int index, @PathVariable double value) {
		switch (index) {
		case 0:
			theLon0 = value;
			break;
		case 1:
			theLon1 = value;
			break;
		default:
			throw new IllegalArgumentException("Illegal index: 0 or 1 allowed");
		}
	}

	@PutMapping("/alt/{index}/{value}")
	public void setAlt(@PathVariable int index, @PathVariable double value) {
		switch (index) {
		case 0:
			theAlt0 = value;
			break;
		case 1:
			theAlt1 = value;
			break;
		default:
			throw new IllegalArgumentException("Illegal index: 0 or 1 allowed");
		}
	}
}
