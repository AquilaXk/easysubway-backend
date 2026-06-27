package com.easysubway.admin.web;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;

public record AdminFormErrorView(List<String> summary, Map<String, String> fieldErrors) {

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
