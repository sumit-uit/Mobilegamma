package com.mobilegamma.cakesync

/** Switches for features that are built but not turned on. */
object Features {
    /**
     * The web order form (docs/order, OrderForm): customers fill in a web page instead of
     * copying text. Off until it has a proper home (its own domain instead of GitHub Pages).
     * To turn it on: set true, enable GitHub Pages (main, /docs) or point
     * OrderForm.BASE_URL at the new host.
     */
    const val WEB_ORDER_FORM = false
}
