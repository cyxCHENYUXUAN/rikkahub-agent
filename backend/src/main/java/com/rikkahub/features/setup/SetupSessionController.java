package com.rikkahub.features.setup;

import com.rikkahub.platform.security.LocalTokenService;
import jakarta.servlet.http.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** OriginGuard verifies the browser or existing header credential before this endpoint runs. */
@RestController
@RequestMapping("/api/v1/setup")
public final class SetupSessionController {
    private final LocalTokenService tokens;
    public SetupSessionController(LocalTokenService tokens) { this.tokens = tokens; }

    @GetMapping("/session") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void session(HttpServletRequest request, HttpServletResponse response) {
        tokens.establishSession(response, request.isSecure());
    }
}
