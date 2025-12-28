package de.schildbach.wallet.rates;

import androidx.annotation.Nullable;

import com.squareup.moshi.FromJson;
import com.squareup.moshi.Json;
import com.squareup.moshi.JsonDataException;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.ToJson;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import de.schildbach.wallet.util.ExplorerConfig;
import retrofit2.Call;
import retrofit2.converter.moshi.MoshiConverterFactory;
import retrofit2.http.GET;

/**
 * Fetches the current USDT price from the configured explorer.
 * BUG FIX #5: Uses ExplorerConfig for dynamic explorer URL.
 */
public class ExplorerPriceClient extends RetrofitClient implements ExchangeRatesClient {

    private static ExplorerPriceClient instance;

    public static ExplorerPriceClient getInstance() {
        if (instance == null) {
            instance = new ExplorerPriceClient();
        }
        return instance;
    }

    /**
     * Force recreation of the singleton to pick up new explorer URL.
     */
    public static void resetInstance() {
        instance = null;
    }

    private final ExplorerPriceService service;

    private ExplorerPriceClient() {
        super(ExplorerConfig.getExplorerBaseUrl() + "/");
        moshiBuilder.add(new BigDecimalAdapter());
        Moshi moshi = moshiBuilder.build();
        retrofit = retrofitBuilder.addConverterFactory(MoshiConverterFactory.create(moshi)).build();
        service = retrofit.create(ExplorerPriceService.class);
    }

    @Override
    @Nullable
    public List<ExchangeRate> getRates() throws Exception {
        ExplorerPriceResponse response = service.getCurrentPrice().execute().body();
        if (response == null) {
            throw new IllegalStateException("Failed to fetch explorer price data");
        }

        BigDecimal price = response.lastPriceUsdt;
        if (price == null) {
            price = response.lastPriceUsd;
        }

        if (price == null) {
            throw new IllegalStateException("Explorer price response did not include the USDT or USD rate");
        }

        List<ExchangeRate> rates = new ArrayList<>(1);
        rates.add(new ExchangeRate("USDT", price.setScale(8, java.math.RoundingMode.HALF_UP).toPlainString()));
        return rates;
    }

    private interface ExplorerPriceService {
        @GET("ext/getcurrentprice")
        Call<ExplorerPriceResponse> getCurrentPrice();
    }

    private static class ExplorerPriceResponse {
        @Json(name = "last_price_usdt")
        BigDecimal lastPriceUsdt;
        @Json(name = "last_price_usd")
        BigDecimal lastPriceUsd;
    }

    private static class BigDecimalAdapter {
        @ToJson
        String toJson(BigDecimal value) {
            return value == null ? null : value.toPlainString();
        }

        @FromJson
        BigDecimal fromJson(JsonReader reader) throws IOException {
            final JsonReader.Token token = reader.peek();
            if (token == JsonReader.Token.NULL) {
                reader.nextNull();
                return null;
            }
            if (token == JsonReader.Token.STRING || token == JsonReader.Token.NUMBER) {
                final String value = reader.nextString();
                if (value == null) {
                    return null;
                }
                try {
                    return new BigDecimal(value);
                } catch (NumberFormatException e) {
                    throw new JsonDataException("Unable to parse BigDecimal value '" + value + "'", e);
                }
            }
            throw new JsonDataException("Expected STRING or NUMBER but was " + token);
        }
    }
}
