package de.schildbach.wallet.data.api;

import com.squareup.moshi.Json;
import java.util.List;

public class HeaderResponse {
    @Json(name = "tip_height")
    public long tipHeight;

    @Json(name = "headers")
    public List<HeaderDto> headers;
}
