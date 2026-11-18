package com.easysubway.common.web;

import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.stereotype.Component;

@Component
public class WebMessageResolver {

	private final MessageSource messageSource;

	public WebMessageResolver(MessageSource messageSource) {
		this.messageSource = messageSource;
	}

	public static WebMessageResolver defaultMessages() {
		ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
		messageSource.setBasename("messages");
		messageSource.setDefaultEncoding("UTF-8");
		messageSource.setFallbackToSystemLocale(false);
		return new WebMessageResolver(messageSource);
	}

	public String message(String code, Object... args) {
		return messageSource.getMessage(code, args, LocaleContextHolder.getLocale());
	}

	public String enumLabel(String prefix, Enum<?> value) {
		if (value == null) {
			throw new NoSuchMessageException(prefix + ".null", LocaleContextHolder.getLocale());
		}
		return message(prefix + "." + value.name());
	}
}
