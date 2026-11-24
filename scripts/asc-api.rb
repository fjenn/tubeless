#!/usr/bin/env ruby
# frozen_string_literal: true
# Minimal App Store Connect API client for scripts/release-ios-testflight.sh.
#
#   ruby scripts/asc-api.rb GET  /v1/apps?filter[bundleId]=dev.tubeless.app
#   ruby scripts/asc-api.rb POST /v1/bundleIds '{"data":{...}}'
#
# Key from the environment: ASC_KEY_PATH (AuthKey_XXXX.p8), ASC_KEY_ID, ASC_ISSUER.
# Prints the JSON answer; exits 1 on an HTTP error (body on stderr). Ruby stdlib only.
require "openssl"
require "json"
require "base64"
require "net/http"
require "uri"

def b64url(bytes) = Base64.urlsafe_encode64(bytes, padding: false)

def asc_token(p8_path, key_id, issuer)
  key = OpenSSL::PKey.read(File.read(p8_path))
  now = Time.now.to_i
  head = b64url(JSON.generate({ alg: "ES256", kid: key_id, typ: "JWT" }))
  body = b64url(JSON.generate({ iss: issuer, iat: now, exp: now + 600, aud: "appstoreconnect-v1" }))
  der = key.sign(OpenSSL::Digest.new("SHA256"), "#{head}.#{body}")
  raw = OpenSSL::ASN1.decode(der).value.map { |i| i.value.to_s(2).rjust(32, "\0".b) }.join
  "#{head}.#{body}.#{b64url(raw)}"
end

abort "usage: #{$PROGRAM_NAME} METHOD /v1/path [json body]" unless (2..3).cover?(ARGV.size)
meth, path, payload = ARGV
%w[ASC_KEY_PATH ASC_KEY_ID ASC_ISSUER].each { |v| abort "#{v} is not set" if ENV[v].to_s.empty? }
uri = URI("https://api.appstoreconnect.apple.com#{path}")
klass = { "GET" => Net::HTTP::Get, "POST" => Net::HTTP::Post, "PATCH" => Net::HTTP::Patch,
          "DELETE" => Net::HTTP::Delete }.fetch(meth.upcase)
req = klass.new(uri, "Authorization" => "Bearer #{asc_token(ENV["ASC_KEY_PATH"], ENV["ASC_KEY_ID"], ENV["ASC_ISSUER"])}",
                     "Content-Type" => "application/json")
req.body = payload if payload
res = Net::HTTP.start(uri.host, uri.port, use_ssl: true, open_timeout: 20, read_timeout: 60) { |h| h.request(req) }
unless res.is_a?(Net::HTTPSuccess)
  warn "HTTP #{res.code}: #{res.body.to_s[0, 2000]}"
  exit 1
end
puts res.body.to_s.empty? ? "{}" : res.body
