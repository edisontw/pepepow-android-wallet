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
        if (org.pepepow.wallet.BuildConfig.DEBUG) {
            org.slf4j.LoggerFactory.getLogger(PaymentsPayFragment::class.java).info("NAV: PaymentsPayFragment created (startDestination reached)")
        }
        handlePaste(false)
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
        // Allow sending if we have a balance, regardless of sync mode (unless wallet is null/closed)
        val application = activity?.application as? de.schildbach.wallet.WalletApplication
        val wallet = application?.wallet
        if (wallet == null) return false

        val balance = wallet.getBalance(org.bitcoinj.wallet.Wallet.BalanceType.AVAILABLE)
        return balance.signum() > 0
    }

    private fun manageStateOfPayToAddressButton(paymentIntent: PaymentIntent?) {
        val canSend = canUserSendCoins() || paymentIntent != null
        if (org.pepepow.wallet.BuildConfig.DEBUG) {
             org.slf4j.LoggerFactory.getLogger(PaymentsPayFragment::class.java).info("PEPEPOW-PAYMENTS: manageStateOfPayToAddressButton: canSend=$canSend (balance>0=${canUserSendCoins()}, clipboard=${paymentIntent != null})")
        }
        pay_to_address.setActive(canSend)

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
