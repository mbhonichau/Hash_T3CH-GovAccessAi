const navigation = document.querySelector("#primary-navigation");
const toggle = document.querySelector(".navigation-toggle");
const mobile = window.matchMedia("(max-width: 1199.98px)");

function setNavigation(open) {
    navigation.hidden = !open;
    toggle.setAttribute("aria-expanded", String(open));
}

toggle.addEventListener("click", () => setNavigation(navigation.hidden));

document.querySelector("#sidebar").addEventListener("keydown", (event) => {
    if (event.key === "Escape" && mobile.matches && !navigation.hidden) {
        setNavigation(false);
        toggle.focus();
    }
});

function updateLayout() {
    const focusWasInNavigation = navigation.contains(document.activeElement);
    setNavigation(!mobile.matches);

    if (mobile.matches && focusWasInNavigation) {
        toggle.focus();
    }
}

toggle.hidden = false;
mobile.addEventListener("change", updateLayout);
updateLayout();
