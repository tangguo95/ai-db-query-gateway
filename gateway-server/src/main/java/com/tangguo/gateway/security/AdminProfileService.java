package com.tangguo.gateway.security;

import com.tangguo.gateway.api.ApiDtos.AdminProfileUpdateRequest;
import com.tangguo.gateway.api.ApiDtos.AdminProfileView;
import com.tangguo.gateway.api.GatewayException;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Stores the local administrator's small, non-secret profile in the local settings database. */
@Service
public class AdminProfileService {
    private static final String DISPLAY_NAME = "admin_profile_display_name";
    private static final String AVATAR = "admin_profile_avatar";
    private static final String DEFAULT_DISPLAY_NAME = "本地管理员";
    private static final int MAX_AVATAR_BYTES = 5 * 1_048_576;
    private static final int MAX_AVATAR_DATA_URL_CHARS = 7_000_000;
    private static final Pattern AVATAR_DATA_URL = Pattern.compile(
            "^data:image/(gif|png|jpeg|webp);base64,([A-Za-z0-9+/]+={0,2})$",
            Pattern.CASE_INSENSITIVE);

    private final SettingService settings;

    public AdminProfileService(SettingService settings) {
        this.settings = settings;
    }

    public AdminProfileView profile(String username) {
        return new AdminProfileView(
                username == null || username.isBlank() ? "admin" : username,
                settings.get(DISPLAY_NAME).filter(value -> !value.isBlank()).orElse(DEFAULT_DISPLAY_NAME),
                settings.get(AVATAR).orElse(null));
    }

    public AdminProfileView update(String username, AdminProfileUpdateRequest request) {
        String currentDisplayName = settings.get(DISPLAY_NAME).orElse(DEFAULT_DISPLAY_NAME);
        String nextDisplayName = currentDisplayName;
        if (request.displayName() != null) {
            nextDisplayName = request.displayName().trim();
            if (nextDisplayName.isBlank()) {
                throw new GatewayException(HttpStatus.BAD_REQUEST, "INVALID_DISPLAY_NAME", "显示名称不能为空");
            }
        }

        String currentAvatar = settings.get(AVATAR).orElse(null);
        String nextAvatar = currentAvatar;
        if (request.avatarDataUrl() != null) {
            nextAvatar = request.avatarDataUrl().trim();
            if (nextAvatar.isBlank()) {
                nextAvatar = null;
            } else {
                validateAvatar(nextAvatar);
            }
        }

        if (request.displayName() != null) {
            settings.put(DISPLAY_NAME, nextDisplayName);
        }
        if (!Objects.equals(currentAvatar, nextAvatar)) {
            if (nextAvatar == null) {
                settings.delete(AVATAR);
            } else {
                settings.put(AVATAR, nextAvatar);
            }
        }
        return profile(username);
    }

    public boolean displayNameChanged(AdminProfileView before, AdminProfileView after) {
        return !Objects.equals(before.displayName(), after.displayName());
    }

    public boolean avatarChanged(AdminProfileView before, AdminProfileView after) {
        return !Objects.equals(before.avatarDataUrl(), after.avatarDataUrl());
    }

    private void validateAvatar(String dataUrl) {
        if (dataUrl.length() > MAX_AVATAR_DATA_URL_CHARS) {
            throw new GatewayException(
                    HttpStatus.BAD_REQUEST, "AVATAR_TOO_LARGE", "头像文件不能超过 5 MiB");
        }
        Matcher matcher = AVATAR_DATA_URL.matcher(dataUrl);
        if (!matcher.matches()) {
            throw new GatewayException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_AVATAR",
                    "头像仅支持 GIF、PNG、JPG 或 WebP 图片");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(matcher.group(2));
            if (decoded.length > MAX_AVATAR_BYTES) {
                throw new GatewayException(
                        HttpStatus.BAD_REQUEST, "AVATAR_TOO_LARGE", "头像文件不能超过 5 MiB");
            }
        } catch (IllegalArgumentException exception) {
            throw new GatewayException(HttpStatus.BAD_REQUEST, "INVALID_AVATAR", "头像文件内容无效");
        }
    }
}
