package de.schildbach.wallet.ui;

import android.content.Context;
import android.content.res.Resources;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.RecyclerView;

import org.bitcoinj.core.Coin;
import org.bitcoinj.utils.MonetaryFormat;
import org.dash.wallet.common.ui.CurrencyTextView;
import org.pepepow.wallet.R;

import java.util.ArrayList;
import java.util.List;

import de.schildbach.wallet.data.api.ApiSessionWallet;

/**
 * Adapter for displaying transactions from ApiSessionWallet.
 * Simplifies the view binding as we don't have full bitcoinj Transaction
 * objects.
 */
public class ApiSessionTransactionsAdapter
        extends RecyclerView.Adapter<ApiSessionTransactionsAdapter.SessionTxViewHolder> {

    private final Context context;
    private final LayoutInflater inflater;
    private final List<ApiSessionWallet.SessionTxItem> items = new ArrayList<>();
    private MonetaryFormat format;

    private final int colorBackground, colorBackgroundSelected;
    private final int colorPrimaryStatus, colorSecondaryStatus;
    private final int colorValuePositive, colorValueNegative;

    // TASK 3: Click listener for transaction details
    private OnItemClickListener listener;

    /**
     * Click listener interface for transaction item clicks.
     */
    public interface OnItemClickListener {
        void onItemClick(ApiSessionWallet.SessionTxItem item);
    }

    /**
     * Set listener for item clicks.
     */
    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    public ApiSessionTransactionsAdapter(Context context) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
        setHasStableIds(true); // Enable stable IDs for smoother updates

        final Resources res = context.getResources();
        colorBackground = res.getColor(R.color.bg_bright);
        colorBackgroundSelected = res.getColor(R.color.bg_panel);
        colorPrimaryStatus = res.getColor(R.color.primary_status);
        colorSecondaryStatus = res.getColor(R.color.secondary_status);
        colorValuePositive = res.getColor(R.color.colorPrimary);
        colorValueNegative = res.getColor(android.R.color.black);
    }

    public void setFormat(final MonetaryFormat format) {
        this.format = format.noCode();
        notifyDataSetChanged();
    }

    public void replace(List<ApiSessionWallet.SessionTxItem> newItems) {
        this.items.clear();
        if (newItems != null) {
            this.items.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public long getItemId(int position) {
        if (position >= 0 && position < items.size()) {
            ApiSessionWallet.SessionTxItem item = items.get(position);
            // Combine txId hash with direction for unique stable ID
            long hash = item.txId.hashCode();
            if (item.direction != null) {
                hash = hash * 31 + item.direction.ordinal();
            }
            return hash;
        }
        return RecyclerView.NO_ID;
    }

    @Override
    public SessionTxViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        return new SessionTxViewHolder(inflater.inflate(R.layout.transaction_row, parent, false));
    }

    @Override
    public void onBindViewHolder(SessionTxViewHolder holder, int position) {
        ApiSessionWallet.SessionTxItem item = items.get(position);
        holder.bind(item);

        // TASK 3: Wire click listener for transaction details
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onItemClick(item);
            }
        });
    }

    public class SessionTxViewHolder extends RecyclerView.ViewHolder {
        private final TextView primaryStatusView;
        private final TextView secondaryStatusView;
        private final TextView timeView;
        private final ImageView dashSymbolView;
        private final CurrencyTextView valueView;
        private final TextView signalView;
        private final CurrencyTextView fiatView;
        private final TextView rateNotAvailableView;

        public SessionTxViewHolder(View itemView) {
            super(itemView);
            primaryStatusView = itemView.findViewById(R.id.transaction_row_primary_status);
            secondaryStatusView = itemView.findViewById(R.id.transaction_row_secondary_status);
            timeView = itemView.findViewById(R.id.transaction_row_time);
            dashSymbolView = itemView.findViewById(R.id.dash_amount_symbol);
            valueView = itemView.findViewById(R.id.transaction_row_value);
            signalView = itemView.findViewById(R.id.transaction_amount_signal);
            fiatView = itemView.findViewById(R.id.transaction_row_fiat);
            rateNotAvailableView = itemView.findViewById(R.id.transaction_row_rate_not_available);

            // Hide fiat/rate views as we don't support exchange rates in this mode yet
            fiatView.setVisibility(View.GONE);
            rateNotAvailableView.setVisibility(View.GONE);
        }

        public void bind(ApiSessionWallet.SessionTxItem item) {
            if (itemView instanceof CardView) {
                ((CardView) itemView).setCardBackgroundColor(colorBackground);
            }

            // Time
            String onTimeText = context.getString(R.string.transaction_row_time_text);
            timeView.setText(String.format(onTimeText,
                    DateUtils.formatDateTime(context, item.timeMs,
                            DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR),
                    DateUtils.formatDateTime(context, item.timeMs, DateUtils.FORMAT_SHOW_TIME)));

            // Value & Status
            boolean isSent = item.valueDelta.signum() < 0;

            // Fix B: Set format BEFORE setAmount to ensure MonetaryFormat is initialized
            // before updateView() is triggered
            if (format != null) {
                valueView.setFormat(format);
            } else {
                // Ensure a format is set to prevent NPE in CurrencyTextView.updateView
                valueView.setFormat(de.schildbach.wallet.Constants.PEPEPOW_FORMAT.noCode());
                android.util.Log.w("ApiSessionTransactionsAdapter", "UI-CURRENCY fallback PEPEPOW_FORMAT applied");
            }

            if (isSent) {
                primaryStatusView.setText(item.isSelfSend ? context.getString(R.string.history_send_to_self_title)
                        : context.getString(R.string.transaction_row_status_sent));
                primaryStatusView.setTextColor(colorPrimaryStatus);
                valueView.setTextColor(colorValueNegative);
                signalView.setTextColor(colorValueNegative);
                dashSymbolView.setColorFilter(colorValueNegative);

                signalView.setText(String.format("%c", org.dash.wallet.common.Constants.CURRENCY_MINUS_SIGN));
                valueView.setAmount(item.valueDelta.negate());
            } else {
                primaryStatusView.setText(R.string.transaction_row_status_received);
                primaryStatusView.setTextColor(colorPrimaryStatus);
                valueView.setTextColor(colorValuePositive);
                signalView.setTextColor(colorValuePositive);
                dashSymbolView.setColorFilter(colorValuePositive);

                signalView.setText(String.format("%c", org.dash.wallet.common.Constants.CURRENCY_PLUS_SIGN));
                valueView.setAmount(item.valueDelta);
            }

            if (item.isSelfSend) {
                android.util.Log.i("History", "[history] bind_self_transfer txid=" + item.txId + " isSelfSend=true");
            }

            signalView.setVisibility(View.VISIBLE);
            valueView.setVisibility(View.VISIBLE);
            dashSymbolView.setVisibility(View.VISIBLE);

            // Secondary Status (Confirmations)
            StringBuilder status = new StringBuilder();
            if (item.confirmations == 0) {
                status.append(context.getString(R.string.transaction_row_status_processing));
            } else {
                status.append(context.getString(R.string.transaction_row_status_confirmations, item.confirmations));
            }
            if (item.isSelfSend) {
                status.append("\n").append(context.getString(R.string.history_send_to_self_note));
                android.util.Log.d("ApiSessionTransactionsAdapter",
                        "[history] self_send_annotated txid=" + item.txId + " outgoingLocal=true outputsToUs=true");
            }
            secondaryStatusView.setText(status.toString());
            secondaryStatusView.setTextColor(colorSecondaryStatus);
        }
    }
}
