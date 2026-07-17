package org.nostr.nostrord.web.screens

import org.nostr.nostrord.ui.screens.backup.BackupViewModel
import org.nostr.nostrord.ui.screens.backup.BackupViewModel.PomegranateDisconnect
import org.nostr.nostrord.ui.screens.backup.BackupViewModel.PomegranateExport
import org.nostr.nostrord.ui.screens.backup.BackupViewModel.ShardStatus
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.copyToClipboard
import org.nostr.nostrord.web.components.formError
import org.nostr.nostrord.web.components.icon
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import react.useState
import web.cssom.ClassName

external interface PomegranateKeySectionProps : Props {
    var vm: BackupViewModel
}

/**
 * Backup-keys section for pomegranate (Login with Google) accounts: explains the
 * sharded-key model, exports the nsec by recovering shards from the operators one
 * popup at a time, and disconnects the account from the central server. States all
 * live in [BackupViewModel]; this component is layout only.
 */
val PomegranateKeySection =
    FC<PomegranateKeySectionProps> { props ->
        val vm = props.vm
        val export = useStateFlow(vm.pomExport)
        val disconnect = useStateFlow(vm.pomDisconnect)
        val pomError = useStateFlow(vm.pomError)
        val (disconnectArmed, setDisconnectArmed) = useState { false }

        div {
            className = ClassName("settings-card")
            div {
                className = ClassName("field-label")
                +"Private key"
            }
            div {
                className = ClassName("settings-tip")
                +(
                    "This account signs in with Google: the key was created for you, split into shards held by " +
                        "independent operators, and never stored whole anywhere. Signing happens remotely (NIP-46) " +
                        "through the central server. You can reassemble and export it below."
                    )
            }
            formError(pomError)
            when (export) {
                PomegranateExport.Idle -> {
                    div {
                        className = ClassName("pom-actions")
                        button {
                            className = ClassName("btn-secondary")
                            onClick = { vm.startPomegranateExport() }
                            +"Export private key"
                        }
                    }
                }

                PomegranateExport.Authing -> {
                    div {
                        className = ClassName("pom-actions")
                        button {
                            className = ClassName("btn-secondary")
                            disabled = true
                            span { className = ClassName("btn-spinner") }
                            +"Waiting for Google sign-in…"
                        }
                    }
                }

                is PomegranateExport.Recovering -> {
                    div {
                        className = ClassName("settings-tip")
                        +"Recovered ${export.recovered} of ${export.threshold} shards. Any ${export.threshold} operators are enough; a failing one can be skipped."
                    }
                    export.operators.forEach { op ->
                        div {
                            key = op.operator.url
                            className = ClassName("pom-operator-row")
                            span {
                                className = ClassName("pom-operator-host")
                                +op.host
                            }
                            when (op.status) {
                                ShardStatus.Recovered -> {
                                    span {
                                        className = ClassName("benefit-check")
                                        icon(Ic.Check)
                                    }
                                }

                                else -> {
                                    button {
                                        className = ClassName("btn-secondary btn-sm")
                                        disabled = export.operators.any { it.status == ShardStatus.Recovering }
                                        onClick = { vm.recoverPomegranateShard(op.operator.url) }
                                        if (op.status == ShardStatus.Recovering) {
                                            span { className = ClassName("btn-spinner") }
                                        }
                                        +when (op.status) {
                                            ShardStatus.Recovering -> "Waiting…"
                                            ShardStatus.Failed -> "Retry"
                                            else -> "Recover"
                                        }
                                    }
                                }
                            }
                        }
                    }
                    div {
                        className = ClassName("pom-actions")
                        button {
                            className = ClassName("btn-text")
                            onClick = { vm.cancelPomegranateExport() }
                            +"Cancel"
                        }
                    }
                }

                is PomegranateExport.Done -> {
                    div {
                        className = ClassName("field-label")
                        +"Private key (nsec)"
                    }
                    div {
                        className = ClassName("pom-nsec")
                        +export.nsec
                    }
                    div {
                        className = ClassName("settings-tip")
                        +"Store it somewhere safe. With the nsec you can log in on any device via the Private Key tab, with or without Google."
                    }
                    div {
                        className = ClassName("pom-actions")
                        button {
                            className = ClassName("btn-secondary")
                            onClick = { copyToClipboard(export.nsec) }
                            +"Copy nsec"
                        }
                        button {
                            className = ClassName("btn-text")
                            onClick = { vm.cancelPomegranateExport() }
                            +"Hide"
                        }
                    }
                }
            }
        }

        div {
            className = ClassName("settings-card warning")
            div {
                className = ClassName("settings-section-head")
                +"DISCONNECT FROM CENTRAL SERVER"
            }
            div {
                className = ClassName("settings-tip")
                +(
                    "Removes this account from the central server: Google login and remote signing stop working. " +
                        "Export your nsec first; without it the account becomes inaccessible."
                    )
            }
            if (disconnect == PomegranateDisconnect.Done) {
                div {
                    className = ClassName("settings-tip")
                    +"Disconnected. This account now works only with its exported key."
                }
            } else {
                div {
                    className = ClassName("pom-actions")
                    button {
                        className = ClassName("btn-danger")
                        disabled = disconnect == PomegranateDisconnect.Working
                        onClick = {
                            if (!disconnectArmed) setDisconnectArmed(true) else vm.disconnectPomegranate()
                        }
                        if (disconnect == PomegranateDisconnect.Working) {
                            span { className = ClassName("btn-spinner") }
                        }
                        +when {
                            disconnect == PomegranateDisconnect.Working -> "Disconnecting…"
                            disconnectArmed -> "Click again to confirm"
                            else -> "Disconnect from central server"
                        }
                    }
                }
            }
        }
    }
