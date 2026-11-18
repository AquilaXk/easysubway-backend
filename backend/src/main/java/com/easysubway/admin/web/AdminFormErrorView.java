package com.easysubway.admin.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;

public record AdminFormErrorView(List<String> summary, Map<String, String> fieldErrors) {

	public AdminFormErrorView {
		summary = summary == null ? null : new ArrayList<>(summary);
		fieldErrors = fieldErrors == null ? null : new LinkedHashMap<>(fieldErrors);
	}

	@Override
	public List<String> summary() {
		return summary == null ? null : new ArrayList<>(summary);
	}

	@Override
	public Map<String, String> fieldErrors() {
		return fieldErrors == null ? null : new LinkedHashMap<>(fieldErrors);
	}

	public static AdminFormErrorView from(BindingResult bindingResult) {
		List<String> summary = bindingResult.getAllErrors()
			.stream()
			.map(error -> error.getDefaultMessage() == null ? "입력값을 확인해야 합니다." : error.getDefaultMessage())
			.toList();
		Map<String, String> fieldErrors = bindingResult.getFieldErrors()
			.stream()
			.collect(Collectors.toMap(
				FieldError::getField,
				error -> error.getDefaultMessage() == null ? "입력값을 확인해야 합니다." : error.getDefaultMessage(),
				(first, ignored) -> first
			));
		return new AdminFormErrorView(summary, fieldErrors);
	}

	public static void expose(Model model, BindingResult bindingResult) {
		AdminFormErrorView view = from(bindingResult);
		model.addAttribute("formErrorSummary", view.summary());
		model.addAttribute("fieldErrors", view.fieldErrors());
	}
}
