package org.nostr.nostrord.web.components

import react.FC
import react.PropsWithChildren
import react.dom.createPortal
import web.dom.document

/**
 * Renders its children into `<body>` through a React portal, so they escape any ancestor that
 * establishes a containing block for fixed positioning.
 *
 * The mobile nav drawer (`.app-frame-nav`) slides in with a `transform`, and a `transform` makes a
 * `position: fixed` descendant resolve relative to the drawer instead of the viewport. A modal
 * opened from inside the drawer/sidebar tree (e.g. the group sidebar's Members / Manage modals)
 * would therefore be clipped to the drawer; portaling it to `<body>` lets the `.modal-overlay`
 * cover the whole screen as intended. React context still flows through the portal, so wrapped
 * modals keep working unchanged. If `<body>` is somehow absent, nothing renders.
 */
val Portal =
    FC<PropsWithChildren> { props ->
        val container = document.body ?: return@FC
        +createPortal(props.children, container)
    }
