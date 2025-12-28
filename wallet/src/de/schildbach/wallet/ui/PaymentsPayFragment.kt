package de.schildbach.wallet.ui

import android.app.Activity
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import de.schildbach.wallet.data.PaymentIntent
import de.schildbach.wallet.ui.scan.ScanActivity
import de.schildbach.wallet.ui.send.SendCoinsActivity
import org.pepepow.wallet.R
import kotlinx.android.synthetic.main.fragment_payments_pay.*
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.VerificationException
import org.bitcoinj.core.PrefixedChecksummedBytes

class PaymentsPayFragment : Fragment() {

    companion object {

        private const val REQUEST_CODE_SCAN = 0

        @JvmStatic
        fun newInstance() = PaymentsPayFragment()
    }

    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private val log = org.slf4j.LoggerFactory.getLogger(PaymentsPayFragment::class.java)
    
    // Prevent repeated auto-paste attempts per fragment instance
    private var hasAttemptedAutoPaste = false

    // Global send enablement state from BlockchainService usability stream
    private var globalSendEnabled = false
    private val usabilityObserver = androidx.lifecycle.Observer<de.schildbach.wallet.service.BlockchainService.WalletUsabilityState> { state ->
        if (state != null) {
            val old = globalSendEnabled
            globalSendEnabled = state.sendEnabled
            if (old != globalSendEnabled) {
                log.info("PAYMENTS-SEND[sid=${fastbootSessionId()}] globalSendEnabled changed to $globalSendEnabled, refreshing UI")
                handlePaste(false)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_payments_pay, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pay_by_qr_button.setOnButtonClickListener(View.OnClickListener {
            handleScan(it)
        })
        pay_to_address.setOnButtonClickListener(View.OnClickListener {
            handlePaste(true)
        })
    }

    override fun onResume() {
        super.onResume()
        val sid = fastbootSessionId()
        
        // Observe usability state for global send enablement
        val application = activity?.application as? de.schildbach.wallet.WalletApplication
        application?.blockchainService?.walletUsabilityLiveData?.observe(viewLifecycleOwner, usabilityObserver)
        (application?.blockchainService as? de.schildbach.wallet.service.BlockchainServiceImpl)
                ?.requestUiRefresh("ON_RESUME")

        // Fix C: Register clipboard listener for live UI updates
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
            Handler(Looper.getMainLooper()).post { 
                log.info("PAYMENTS-SEND clipboard changed, re-evaluating button state")
                handlePaste(false) 
            }
        }
        cm.addPrimaryClipChangedListener(clipboardListener)
        
        // BUG FIX #4: Explicitly refresh send button state on onResume
        // This ensures that after send completion, the button is re-enabled
        val enabled = canUserSendCoins()
        log.info("PaymentsUI[sid=$sid] onResume refreshSendButtonState enabled=$enabled")
        
        // FORCE UPDATE: Immediately update button state (ignore delay)
        try {
            handlePaste(false)
        } catch (e: Exception) {
             // Ignore (e.g. background restriction), will retry in tryAutoPaste
        }
        
        // Fix A: Delay auto-paste to allow window focus, only attempt once per instance
        if (!hasAttemptedAutoPaste) {
            hasAttemptedAutoPaste = true
            view?.postDelayed({ tryAutoPaste() }, 250)
        }
    }

    override fun onPause() {
        super.onPause()
        // Fix C: Unregister clipboard listener
        clipboardListener?.let {
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.removePrimaryClipChangedListener(it)
        }
        clipboardListener = null
        
        // LiveData observer is automatically removed by viewLifecycleOwner
    }

    /**
     * Fix A: Attempt auto-paste with SecurityException handling.
     * Called with delay after onResume to ensure window focus.
     */
    private fun tryAutoPaste() {
        val sid = fastbootSessionId()
        try {
            val input = getClipboardTextNow()
            if (input != null) {
                log.info("PAYMENTS-PASTE[sid=$sid] attempt=onResume result=ok clip_length=${input.length}")
            } else {
                log.info("PAYMENTS-PASTE[sid=$sid] attempt=onResume result=null")
            }
            handlePaste(false)
        } catch (e: SecurityException) {
            log.info("PAYMENTS-PASTE[sid=$sid] attempt=onResume result=denied msg=${e.message}")
            // Don't mark as failure, keep UI interactive for manual paste
            manageStateOfPayToAddressButton(null)
        } catch (e: Exception) {
            log.warn("PAYMENTS-PASTE[sid=$sid] attempt=onResume result=error", e)
            manageStateOfPayToAddressButton(null)
        }
    }

    private fun handleScan(clickView: View) {
        ScanActivity.startForResult(this, activity, REQUEST_CODE_SCAN)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        if (requestCode == REQUEST_CODE_SCAN && resultCode == Activity.RESULT_OK) {
            val input = intent!!.getStringExtra(ScanActivity.INTENT_EXTRA_RESULT)
            handleString(input, true, R.string.button_scan)
        } else {
            super.onActivityResult(requestCode, resultCode, intent)
        }
    }

    private fun canUserSendCoins(): Boolean {
        val application = activity?.application as? de.schildbach.wallet.WalletApplication
        val sid = fastbootSessionId()
        
        // Rule A: Respect the global usability stream if available
        if (globalSendEnabled) {
            log.info("PAYMENTS-SEND[sid=$sid] enabled=true reason=globalSendEnabled")
            return true
        }

        // Rule B: Manual check for API_SESSION (redundant but safe)
        val blockchainService = application?.blockchainService
        val sessionWallet = blockchainService?.sessionWallet
        if (sessionWallet != null && sessionWallet.isReady) {
            val sessionSpendable = sessionWallet.spendableBalance
            val sessionBalance = sessionWallet.balance
            val enabled = sessionSpendable.signum() > 0
            log.info("PAYMENTS-SEND[sid=$sid] src=API_SESSION enabled=$enabled spendable=${sessionSpendable.toFriendlyString()} total=${sessionBalance.toFriendlyString()}")
            return enabled
        }

        // Rule C: Fallback to SPV wallet
        val wallet = application?.wallet
        if (wallet == null) {
            log.info("PAYMENTS-SEND[sid=$sid] src=SPV enabled=false reason=wallet_null")
            return false
        }
        val balance = wallet.getBalance(org.bitcoinj.wallet.Wallet.BalanceType.AVAILABLE)
        val enabled = balance.signum() > 0
        log.info("PAYMENTS-SEND[sid=$sid] src=SPV enabled=$enabled balance=${balance.toFriendlyString()}")
        return enabled
    }

    private fun manageStateOfPayToAddressButton(paymentIntent: PaymentIntent?) {
        // Clipboard shortcut enablement: based on BOTH global send enabled AND valid address
        val balanceEnabled = canUserSendCoins()
        val addressValid = paymentIntent != null
        val canClick = balanceEnabled && addressValid
        
        log.info("PAYMENTS-SEND[sid=${fastbootSessionId()}] manageState: balanceEnabled=$balanceEnabled addressValid=$addressValid canClick=$canClick")
        
        pay_to_address.setActive(canClick)

        if (paymentIntent != null) {
            pay_to_address.setSubTitle(paymentIntent.address.toBase58())
        } else {
            // keep original UX subtitle
            pay_to_address.setSubTitle(R.string.payments_pay_to_clipboard_sub_title)
        }
    }

    private fun handlePaste(fireAction: Boolean) {
        if (org.pepepow.wallet.BuildConfig.DEBUG) {
            org.slf4j.LoggerFactory.getLogger(PaymentsPayFragment::class.java).info("PEPEPOW-PAYMENTS handlePaste(fireAction=$fireAction)")
        }
        val input = getClipboardTextNow()
        if (org.pepepow.wallet.BuildConfig.DEBUG) {
            org.slf4j.LoggerFactory.getLogger(PaymentsPayFragment::class.java).info("PEPEPOW-PAYMENTS handlePaste(): clipboard='$input'")
        }

        if (input.isNullOrBlank()) {
            if (fireAction) {
                android.widget.Toast.makeText(requireContext(), R.string.payments_pay_to_clipboard_no_address, android.widget.Toast.LENGTH_SHORT).show()
            }
            manageStateOfPayToAddressButton(null)
            return
        }

        object : InputParser.StringInputParser(input) {
            override fun handlePaymentIntent(paymentIntent: PaymentIntent) {
                if (fireAction) {
                    SendCoinsActivity.start(context, paymentIntent, true)
                } else {
                    manageStateOfPayToAddressButton(paymentIntent)
                }
            }

            override fun error(messageResId: Int, vararg messageArgs: Any) {
                // Fallback: try to parse as plain base58 address
                if (org.pepepow.wallet.BuildConfig.DEBUG) {
                    org.slf4j.LoggerFactory.getLogger(PaymentsPayFragment::class.java).debug("PEPEPOW-PAYMENTS handlePaste(): InputParser returned null, trying fallback for input='$input'")
                }

                val paymentIntent = parsePlainAddressOrNull(input)

                if (paymentIntent != null) {
                    if (org.pepepow.wallet.BuildConfig.DEBUG) {
                        org.slf4j.LoggerFactory.getLogger(PaymentsPayFragment::class.java).debug("PEPEPOW-PAYMENTS handlePaste(): fallback success, paymentIntent=$paymentIntent")
                    }
                    if (fireAction) {
                        SendCoinsActivity.start(context, paymentIntent, true)
                    } else {
                        manageStateOfPayToAddressButton(paymentIntent)
                    }
                } else {
                    // Both parsers failed
                    if (org.pepepow.wallet.BuildConfig.DEBUG) {
                        org.slf4j.LoggerFactory.getLogger(PaymentsPayFragment::class.java).debug("PEPEPOW-PAYMENTS handlePaste(): fallback failed")
                    }
                    if (fireAction) {
                        android.widget.Toast.makeText(requireContext(), R.string.payments_pay_to_clipboard_no_address, android.widget.Toast.LENGTH_SHORT).show()
                    }
                    manageStateOfPayToAddressButton(null)
                }
            }

            override fun handlePrivateKey(key: PrefixedChecksummedBytes) {
                // Not supported here
            }

            override fun handleDirectTransaction(tx: Transaction) {
                // Not supported here
            }
        }.parse()
    }

    private fun parsePlainAddressOrNull(raw: String?): PaymentIntent? {
        val text = raw?.trim() ?: return null
        if (text.isEmpty()) return null

        return try {
            val params = de.schildbach.wallet.Constants.NETWORK_PARAMETERS
            val address = org.bitcoinj.core.Address.fromString(params, text)
            PaymentIntent.fromAddress(address, null)
        } catch (e: Exception) {
            if (org.pepepow.wallet.BuildConfig.DEBUG) {
                org.slf4j.LoggerFactory.getLogger(PaymentsPayFragment::class.java).debug("parsePlainAddressOrNull: '$text' is not a valid address: ${e.message}")
            }
            null
        }
    }

    private fun getClipboardTextNow(): String? {
        val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null

        val text = clip.getItemAt(0).coerceToText(requireContext())?.toString()?.trim()
        return if (text.isNullOrEmpty()) null else text
    }

    private fun fastbootSessionId(): String {
        val application = activity?.application as? de.schildbach.wallet.WalletApplication
        val sessionWallet = application?.blockchainService?.sessionWallet
        return sessionWallet?.sessionId ?: de.schildbach.wallet.ui.WalletReadiness.UI_SESSION_ID
    }



    private fun handleString(input: String, fireAction: Boolean, errorDialogTitleResId: Int) {
        object : InputParser.StringInputParser(input) {

            override fun handlePaymentIntent(paymentIntent: PaymentIntent) {
                if (fireAction) {
                    SendCoinsActivity.start(context, paymentIntent, true)
                } else {
                    manageStateOfPayToAddressButton(paymentIntent)
                }
            }

            override fun error(messageResId: Int, vararg messageArgs: Any) {
                if (fireAction) {
                    if (de.schildbach.wallet.Constants.FAST_API_10POW_ENABLED_FOR_CORE) {
                        SendCoinsActivity.start(context, null, true)
                    } else {
                        InputParser.dialog(context, null, errorDialogTitleResId, messageResId, *messageArgs)
                    }
                } else {
                    manageStateOfPayToAddressButton(null)
                }
            }

            override fun handlePrivateKey(key: PrefixedChecksummedBytes) {

            }

            @Throws(VerificationException::class)
            override fun handleDirectTransaction(tx: Transaction) {

            }
        }.parse()
    }

}
