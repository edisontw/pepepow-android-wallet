package de.schildbach.wallet.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import org.bitcoinj.utils.MonetaryFormat;
import org.dash.wallet.common.Configuration;
import org.dash.wallet.common.ui.CurrencyTextView;
import org.pepepow.wallet.R;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.data.api.ApiSessionWallet;
import de.schildbach.wallet.util.ExplorerConfig;
import de.schildbach.wallet.util.Toast;

/**
 * BottomSheet dialog for displaying session transaction details.
 * Used when clicking a history row in API_SESSION mode.
 */
public class SessionTransactionDetailsBottomSheet extends BottomSheetDialogFragment {
    private static final Logger log = LoggerFactory.getLogger(SessionTransactionDetailsBottomSheet.class);

    private static final String ARG_TX_ID = "tx_id";
    private static final String ARG_TIME_MS = "time_ms";
    private static final String ARG_AMOUNT_SAT = "amount_sat";
    private static final String ARG_CONFIRMATIONS = "confirmations";
    private static final String ARG_DIRECTION = "direction";
    private static final String ARG_IS_SELF_SEND = "is_self_send";

    private String txId;
    private long timeMs;
    private long amountSat;
    private int confirmations;
    private String direction;
    private boolean isSelfSend;

    public static SessionTransactionDetailsBottomSheet newInstance(ApiSessionWallet.SessionTxItem item) {
        SessionTransactionDetailsBottomSheet fragment = new SessionTransactionDetailsBottomSheet();
        Bundle args = new Bundle();
        args.putString(ARG_TX_ID, item.txId);
        args.putLong(ARG_TIME_MS, item.timeMs);
        args.putLong(ARG_AMOUNT_SAT, item.valueDelta.value);
        args.putInt(ARG_CONFIRMATIONS, item.confirmations);
        args.putString(ARG_DIRECTION, item.direction != null ? item.direction.name() : "RECEIVED");
        args.putBoolean(ARG_IS_SELF_SEND, item.isSelfSend);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            txId = getArguments().getString(ARG_TX_ID);
            timeMs = getArguments().getLong(ARG_TIME_MS, 0L);
            amountSat = getArguments().getLong(ARG_AMOUNT_SAT, 0L);
            confirmations = getArguments().getInt(ARG_CONFIRMATIONS, 0);
            direction = getArguments().getString(ARG_DIRECTION, "RECEIVED");
            isSelfSend = getArguments().getBoolean(ARG_IS_SELF_SEND, false);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.session_transaction_details_bottom_sheet, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Get views
        ImageView directionIcon = view.findViewById(R.id.tx_direction_icon);
        TextView directionText = view.findViewById(R.id.tx_direction_text);
        TextView statusText = view.findViewById(R.id.tx_status_text);
        CurrencyTextView amountView = view.findViewById(R.id.tx_amount);
        TextView timeView = view.findViewById(R.id.tx_time);
        TextView confirmationsView = view.findViewById(R.id.tx_confirmations);
        TextView txIdView = view.findViewById(R.id.tx_id);
        ImageButton copyButton = view.findViewById(R.id.tx_copy_button);
        Button viewExplorerButton = view.findViewById(R.id.tx_view_explorer);

        // Direction
        boolean isSent = "SENT".equals(direction);
        if (isSent) {
            directionIcon.setImageResource(R.drawable.ic_transaction_sent);
            directionText.setText(R.string.transaction_row_status_sent);
            directionText.setTextColor(getResources().getColor(android.R.color.black));
        } else {
            directionIcon.setImageResource(R.drawable.ic_transaction_received);
            directionText.setText(R.string.transaction_row_status_received);
            directionText.setTextColor(getResources().getColor(R.color.colorPrimary));
        }

        // Status
        if (confirmations == 0) {
            statusText.setText(R.string.transaction_row_status_processing);
        } else {
            statusText.setText(R.string.transaction_row_status_confirming);
        }

        // Amount
        ImageView amountIcon = view.findViewById(R.id.tx_amount_icon);
        Configuration config = WalletApplication.getInstance().getConfiguration();
        MonetaryFormat format = config != null ? config.getFormat().noCode() : Constants.PEPEPOW_FORMAT.noCode();
        amountView.setFormat(format);
        org.bitcoinj.core.Coin amount = org.bitcoinj.core.Coin.valueOf(Math.abs(amountSat));
        amountView.setAmount(amount);

        if (isSent) {
            amountView.setTextColor(getResources().getColor(android.R.color.black));
            amountIcon.setColorFilter(getResources().getColor(android.R.color.black));
        } else {
            amountView.setTextColor(getResources().getColor(R.color.colorPrimary));
            amountIcon.setColorFilter(getResources().getColor(R.color.colorPrimary));
        }

        // Time
        String dateStr = DateUtils.formatDateTime(requireContext(), timeMs,
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_SHOW_YEAR);
        timeView.setText(dateStr);

        // Confirmations
        if (confirmations == 0) {
            confirmationsView.setText(R.string.transaction_row_status_processing);
        } else {
            confirmationsView.setText(String.valueOf(confirmations));
        }

        // Transaction ID
        txIdView.setText(txId);

        // Self-send Note
        if (isSelfSend) {
            String existingStatus = statusText.getText().toString();
            statusText.setText(existingStatus + "\n\n" + getString(R.string.history_send_to_self_note));
        }

        // Copy button
        copyButton.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) requireContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                ClipData clip = ClipData.newPlainText("Transaction ID", txId);
                clipboard.setPrimaryClip(clip);
                new Toast(requireContext()).toast(R.string.wallet_address_fragment_clipboard_msg);
                log.info("TX_DETAILS txid copied to clipboard: {}", txId);
            }
        });

        // View on Explorer
        viewExplorerButton.setOnClickListener(v -> {
            try {
                // BUG FIX #5: Use ExplorerConfig for dynamic explorer URL
                String txUrl = ExplorerConfig.getTxBrowserUrl(txId);
                Uri txUri = Uri.parse(txUrl);
                Intent intent = new Intent(Intent.ACTION_VIEW, txUri);
                startActivity(intent);
                log.info("TX_DETAILS opening explorer: {}", txUrl);
            } catch (Exception e) {
                log.warn("TX_DETAILS failed to open explorer: {}", e.getMessage());
            }
        });

        log.info("TX_DETAILS opened txid={} direction={} amount={} confirmations={}",
                txId, direction, amountSat, confirmations);
    }
}
