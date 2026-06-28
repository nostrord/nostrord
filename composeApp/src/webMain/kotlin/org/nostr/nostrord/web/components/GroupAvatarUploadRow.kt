package org.nostr.nostrord.web.components

import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import web.cssom.ClassName

external interface GroupAvatarUploadRowProps : Props {
    var pictureUrl: String?
    var seed: String
    var name: String
    var onPictureChange: (String) -> Unit
    var onError: (String?) -> Unit
}

/**
 * Group avatar preview + "Change photo" upload. Shared by Create Group and Manage > Info so the
 * picture is edited the same way in both.
 */
val GroupAvatarUploadRow =
    FC<GroupAvatarUploadRowProps> { props ->
        div {
            className = ClassName("avatar-upload-row")
            WebAvatar {
                url = props.pictureUrl?.ifBlank { null }
                seed = props.seed
                this.name = props.name.ifBlank { props.seed }
                kind = AvatarKind.GROUP
                cls = "avatar-upload"
            }
            UploadButton {
                cls = "btn-secondary"
                icon = Ic.Upload
                label = "Change photo"
                imagesOnly = true
                onUploaded = { props.onPictureChange(it.url) }
                onError = { props.onError(it) }
            }
        }
    }
