document.querySelectorAll("[data-language]").forEach((button) => {
	button.addEventListener("click", () => {
		const language = button.dataset.language;
		document.documentElement.lang = language;
		document.body.classList.toggle("is-english", language === "en");
		document.querySelectorAll("[data-language]").forEach((option) => {
			const isActive = option === button;
			option.classList.toggle("is-active", isActive);
			option.setAttribute("aria-pressed", String(isActive));
		});
	});
});
