package org.nostr.nostrord.web.components

import react.ChildrenBuilder
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import web.cssom.ClassName
import web.html.InputType

/**
 * Form building blocks shared by the auth surfaces (login screen, key form,
 * add-account sheet, unlock modal) — the markup counterpart of the Compose
 * `ui/components/forms` composables and the `.form-*` / `.input-*` / `.tab`
 * component classes in styles.css.
 */

/** Small uppercase bold label above an input (`.form-label`). */
fun ChildrenBuilder.formLabel(
    text: String,
    spaced: Boolean = false,
) {
    div {
        className = ClassName(if (spaced) "form-label spaced" else "form-label")
        +text
    }
}

/** Muted helper text below an input (`.form-hint`). */
fun ChildrenBuilder.formHint(text: String) {
    p {
        className = ClassName("form-hint")
        +text
    }
}

/** Red error banner (`.form-error`); renders nothing for a null message. */
fun ChildrenBuilder.formError(message: String?) {
    message?.let {
        p {
            className = ClassName("form-error")
            +it
        }
    }
}

/** Horizontal "or" divider between alternative actions (`.form-divider`). */
fun ChildrenBuilder.formDivider(label: String = "or") {
    div {
        className = ClassName("form-divider")
        span { +label }
    }
}

/** One segmented tab inside a `.tab-strip` container (`.tab`). */
fun ChildrenBuilder.tabItem(
    selected: Boolean,
    ic: Ic?,
    label: String,
    onSelect: () -> Unit,
) {
    button {
        className = ClassName(if (selected) "tab selected" else "tab")
        onClick = { onSelect() }
        ic?.let { icon(it) }
        span { +label }
    }
}

/**
 * Icon input row (`.input-group` + `.input-icon` + `.input`): leading icon, the
 * input itself, and an optional trailing control (e.g. the show/hide eye).
 * Enter submits via [onEnter].
 */
fun ChildrenBuilder.iconInput(
    ic: Ic,
    type: InputType,
    placeholder: String,
    value: String,
    autoFocus: Boolean = false,
    onChange: (String) -> Unit,
    onEnter: () -> Unit = {},
    trailing: (ChildrenBuilder.() -> Unit)? = null,
) {
    div {
        className = ClassName("input-group")
        span {
            className = ClassName("input-icon")
            icon(ic)
        }
        input {
            className = ClassName("input")
            this.type = type
            this.placeholder = placeholder
            this.value = value
            this.autoFocus = autoFocus
            this.onChange = { event -> onChange(event.currentTarget.value) }
            onKeyDown = { event -> if (event.key == "Enter") onEnter() }
        }
        trailing?.invoke(this)
    }
}
