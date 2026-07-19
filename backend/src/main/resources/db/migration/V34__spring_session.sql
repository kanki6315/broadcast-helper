-- Spring Session JDBC: HTTP sessions move from Tomcat's memory into Postgres so
-- a redeploy no longer signs everyone out (the app ships often while it's still
-- evolving). Costs ~2 queries per authenticated request — a PK lookup to load
-- and an UPDATE of LAST_ACCESS_TIME, which is what makes idle expiry work —
-- trivial next to the season aggregation queries.
--
-- Copied verbatim from spring-session-jdbc 3.5.1
-- (org/springframework/session/jdbc/schema-postgresql.sql). Flyway owns the
-- schema, so spring.session.jdbc.initialize-schema is set to `never`; re-copy
-- this file into a new migration if a Spring Session upgrade changes it.
-- Unquoted identifiers fold to lower case in Postgres, so these land as
-- spring_session / spring_session_attributes.
--
-- Session attributes (including the SecurityContext holding the OidcUser and
-- its ID token) are Java-serialized into ATTRIBUTE_BYTES. A Spring Security
-- upgrade can therefore invalidate stored sessions — the symptom is
-- deserialization errors on the first request after the upgrade, and the fix is
-- TRUNCATE spring_session (everyone signs in again, as they do today).
CREATE TABLE SPRING_SESSION (
	PRIMARY_ID CHAR(36) NOT NULL,
	SESSION_ID CHAR(36) NOT NULL,
	CREATION_TIME BIGINT NOT NULL,
	LAST_ACCESS_TIME BIGINT NOT NULL,
	MAX_INACTIVE_INTERVAL INT NOT NULL,
	EXPIRY_TIME BIGINT NOT NULL,
	PRINCIPAL_NAME VARCHAR(100),
	CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
	SESSION_PRIMARY_ID CHAR(36) NOT NULL,
	ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
	ATTRIBUTE_BYTES BYTEA NOT NULL,
	CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
	CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID) REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE
);
