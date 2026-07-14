package org.nostr.nostrord.web.components

import react.ChildrenBuilder
import react.FC
import react.Props
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
    // Opt-in: when set, Escape blurs the field and invokes this (filters clear with it).
    onEscape: (() -> Unit)? = null,
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
            onKeyDown = { event ->
                when (event.key) {
                    "Enter" -> onEnter()
                    "Escape" ->
                        if (onEscape != null) {
                            onEscape()
                            event.currentTarget.blur()
                        }
                }
            }
        }
        trailing?.invoke(this)
    }
}

/**
 * Standard search box reused everywhere (OOCSS): the shared `.input-group` object with a leading
 * magnifier and a trailing clear (X). [compact] switches to the smaller `.input-group sm` size for
 * dense lists (member rosters, pickers).
 *
 * When [onClose] is set the trailing X is always shown and dismisses the whole box instead of just
 * clearing the text, and Escape does the same (prototype behavior for a toggleable search). When it
 * is null the X only appears once there is text and both it and Escape clear the field.
 */
fun ChildrenBuilder.searchInput(
    placeholder: String,
    value: String,
    onChange: (String) -> Unit,
    compact: Boolean = false,
    autoFocus: Boolean = false,
    onEnter: () -> Unit = {},
    onClose: (() -> Unit)? = null,
) {
    div {
        className = ClassName(if (compact) "input-group sm" else "input-group")
        span {
            className = ClassName("input-icon")
            icon(Ic.Search)
        }
        input {
            className = ClassName("input")
            this.placeholder = placeholder
            this.value = value
            this.autoFocus = autoFocus
            this.onChange = { event -> onChange(event.currentTarget.value) }
            onKeyDown = { event ->
                when (event.key) {
                    "Enter" -> onEnter()
                    "Escape" ->
                        if (onClose != null) {
                            onClose()
                        } else {
                            onChange("")
                            event.currentTarget.blur()
                        }
                }
            }
        }
        if (onClose != null) {
            button {
                className = ClassName("input-clear")
                onClick = { onClose() }
                icon(Ic.Close)
            }
        } else if (value.isNotEmpty()) {
            button {
                className = ClassName("input-clear")
                onClick = { onChange("") }
                icon(Ic.Close)
            }
        }
    }
}

private external interface ConfirmDialogProps : Props {
    var title: String
    var body: String
    var confirmLabel: String
    var cancelLabel: String
    var danger: Boolean
    var confirmDisabled: Boolean
    var cancelDisabled: Boolean
    var onCancel: () -> Unit
    var onConfirm: () -> Unit

    /** Backdrop/Esc handler when the cancel button is an action of its own; defaults to [onCancel]. */
    var onDismiss: (() -> Unit)?
}

/**
 * The confirm dialog body. An FC (not a bare builder) so it can own an [useEscClose] handler:
 * stacked over another modal, Escape closes only this one (it sits on top of the shared esc stack),
 * not the modal underneath.
 */
private val ConfirmDialogFc =
    FC<ConfirmDialogProps> { props ->
        val dismiss = props.onDismiss ?: props.onCancel
        useEscClose { if (!props.cancelDisabled) dismiss() }
        div {
            className = ClassName("modal-overlay")
            onClick = { if (!props.cancelDisabled) dismiss() }
            div {
                className = ClassName("modal-card sm")
                onClick = { it.stopPropagation() }
                div {
                    className = ClassName("modal-title")
                    +props.title
                }
                div {
                    className = ClassName("modal-subtitle tight")
                    +props.body
                }
                div {
                    className = ClassName("modal-footer")
                    button {
                        className = ClassName("btn-text")
                        disabled = props.cancelDisabled
                        onClick = { props.onCancel() }
                        +props.cancelLabel
                    }
                    button {
                        className = ClassName(if (props.danger) "btn-danger" else "btn-primary")
                        disabled = props.confirmDisabled
                        onClick = { props.onConfirm() }
                        +props.confirmLabel
                    }
                }
            }
        }
    }

/**
 * The single confirm dialog for the web (`.modal-card.sm` + `.modal-title` / `.modal-subtitle` /
 * `.modal-footer`): a title, a body line, and a Cancel / confirm pair. [danger] makes the confirm
 * button red for destructive actions. Every destructive or irreversible confirm (delete message,
 * remove member, leave group, log out) routes through this so the dialogs read identically and the
 * overlay markup is not re-hand-rolled per call site. Clicking the backdrop (or Escape) cancels,
 * unless [cancelDisabled] (e.g. an action is in flight); [confirmDisabled] gates the confirm button.
 *
 * [onDismiss], when set, makes the cancel button an ACTION of its own instead of a close
 * (backdrop/Esc run [onDismiss]) — for prompts whose two buttons are both decisions, like
 * the group-invite Accept/Decline.
 */
fun ChildrenBuilder.confirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    danger: Boolean = false,
    cancelLabel: String = "Cancel",
    confirmDisabled: Boolean = false,
    cancelDisabled: Boolean = false,
    onDismiss: (() -> Unit)? = null,
) {
    ConfirmDialogFc {
        this.title = title
        this.body = body
        this.confirmLabel = confirmLabel
        this.cancelLabel = cancelLabel
        this.danger = danger
        this.confirmDisabled = confirmDisabled
        this.cancelDisabled = cancelDisabled
        this.onCancel = onCancel
        this.onConfirm = onConfirm
        this.onDismiss = onDismiss
    }
}
