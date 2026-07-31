-- Most wordmark logos are dark-on-transparent, which the sheet's dark theme
-- handles by painting a white pill behind them. For monochrome marks the
-- broadcaster can instead flag the logo to be recoloured white in dark mode
-- (a CSS filter client-side), keeping the dark ground clean. Off by default:
-- multi-colour badges (roundels, crests) collapse into a silhouette when
-- inverted, so a human opts each logo in from Manage → Logos.

ALTER TABLE manufacturer_logo
    ADD COLUMN invert_on_dark BOOLEAN NOT NULL DEFAULT FALSE;
