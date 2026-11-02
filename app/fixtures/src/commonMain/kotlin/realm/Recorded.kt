// Cloudflare's replies as it really sent them to read-only requests on 2026-10-31, with the account's id
// and name, zones, database ids, subdomain and other workers and buckets swapped for Jo's. Everything else,
// down to null against [], is as sent: hand-written replies elsewhere copy their envelope.
package realm

/** What Cloudflare answers about Jo's account a1, with the realm worker on workers.dev and no custom domain. */
object Recorded {
    /** GET /accounts. */
    const val ACCOUNTS = """
{
  "result": [
    {
      "id": "a1",
      "name": "Jo's account",
      "type": "standard",
      "settings": {
        "enforce_twofactor": false,
        "api_access_enabled": null,
        "access_approval_expiry": null,
        "abuse_contact_email": null,
        "oauth_app_access_enabled": true
      },
      "legacy_flags": {
        "enterprise_zone_quota": {
          "maximum": 0,
          "current": 0,
          "available": 0
        }
      },
      "created_on": "2025-04-21T11:44:31.593793Z"
    }
  ],
  "result_info": {
    "page": 1,
    "per_page": 20,
    "total_pages": 1,
    "count": 1,
    "total_count": 1
  },
  "success": true,
  "errors": [],
  "messages": []
}
"""

    /** GET /accounts/a1/workers/scripts: the realm worker and one other. */
    const val SCRIPTS = """
{
  "result": [
    {
      "created_on": "2026-06-09T23:40:32.503446Z",
      "modified_on": "2026-06-11T08:52:18.938122Z",
      "id": "notes",
      "tag": "8caa0fb6b5f642bebb591ee59ad67d4e",
      "tags": [],
      "deployment_id": "",
      "tail_consumers": null,
      "logpush": false,
      "has_assets": true,
      "has_modules": false,
      "etag": "",
      "handlers": [
        "fetch"
      ],
      "last_deployed_from": "",
      "compatibility_date": "2025-06-01",
      "usage_model": "standard",
      "routes": null
    },
    {
      "created_on": "2026-10-30T18:31:09.980202Z",
      "modified_on": "2026-10-31T11:31:32.757565Z",
      "id": "realm",
      "tag": "801cbd7bb870416d94e713c2ec26aea6",
      "tags": [],
      "deployment_id": "",
      "tail_consumers": null,
      "logpush": false,
      "has_assets": false,
      "has_modules": true,
      "etag": "89eb1230757ea4436bcc045b8788fb4ff1450961d063081c71e329da39827ea3",
      "handlers": [
        "fetch",
        "scheduled"
      ],
      "named_handlers": [
        {
          "name": "owners",
          "handlers": [
            "get",
            "set",
            "has",
            "delete",
            "clear",
            "entries",
            "forEach",
            "keys",
            "size",
            "values",
            "getOrInsert",
            "getOrInsertComputed"
          ]
        }
      ],
      "last_deployed_from": "api",
      "compatibility_date": "2026-10-01",
      "usage_model": "standard",
      "routes": null
    }
  ],
  "success": true,
  "errors": [],
  "messages": []
}
"""

    /** GET /accounts/a1/workers/domains?service=realm with none: errors and messages are null here, unlike elsewhere. */
    const val DOMAINS = """
{
  "result": [],
  "success": true,
  "errors": null,
  "messages": null,
  "result_info": {
    "page": 1,
    "per_page": 0,
    "count": 0,
    "total_count": 0
  }
}
"""

    /** GET /accounts/a1/workers/subdomain. */
    const val SUBDOMAIN = """
{
  "result": {
    "subdomain": "jo"
  },
  "success": true,
  "errors": [],
  "messages": []
}
"""

    /** GET /accounts/a1/workers/scripts/realm/subdomain. */
    const val SCRIPT_SUBDOMAIN = """
{
  "result": {
    "enabled": true,
    "previews_enabled": true
  },
  "success": true,
  "errors": [],
  "messages": []
}
"""

    /** GET /accounts/a1/workers/scripts/realm/secrets: names only, never values. */
    const val SECRETS = """
{
  "result": [
    {
      "name": "REALM_ACCOUNT",
      "type": "secret_text"
    },
    {
      "name": "REALM_TOKEN",
      "type": "secret_text"
    }
  ],
  "success": true,
  "errors": [],
  "messages": []
}
"""

    /** GET /accounts/a1/r2/buckets. */
    const val BUCKETS = """
{
  "success": true,
  "errors": [],
  "messages": [],
  "result": {
    "buckets": [
      {
        "name": "photos",
        "creation_date": "2025-08-13T15:00:23.022Z"
      },
      {
        "name": "backups",
        "creation_date": "2025-12-27T10:04:14.289Z"
      },
      {
        "name": "notes",
        "creation_date": "2026-07-27T20:24:14.270Z"
      },
      {
        "name": "realm",
        "creation_date": "2026-10-30T18:23:12.199Z"
      }
    ]
  },
  "result_info": {
    "total_count": 4
  }
}
"""

    /** GET /accounts/a1/d1/database?name=realm. */
    const val D1 = """
{
  "result": [
    {
      "uuid": "db1",
      "name": "realm",
      "created_at": "2026-10-30T18:31:08.144Z",
      "version": "production",
      "num_tables": 0,
      "file_size": 98304,
      "jurisdiction": null
    }
  ],
  "errors": [],
  "messages": [],
  "success": true,
  "result_info": {
    "count": 1,
    "page": 1,
    "per_page": 100,
    "total_count": 1
  }
}
"""

    /** GET /zones?account.id=a1. */
    const val ZONES = """
{
  "result": [
    {
      "id": "z1",
      "name": "jo.dev",
      "status": "active",
      "paused": false,
      "type": "full",
      "development_mode": 0,
      "name_servers": [
        "jade.ns.cloudflare.com",
        "mark.ns.cloudflare.com"
      ],
      "original_name_servers": [
        "maceio.ns.porkbun.com",
        "curitiba.ns.porkbun.com",
        "salvador.ns.porkbun.com",
        "fortaleza.ns.porkbun.com"
      ],
      "original_registrar": "porkbun llc (id: 1861)",
      "original_dnshost": null,
      "modified_on": "2025-07-27T14:01:19.360903Z",
      "created_on": "2025-07-27T13:35:31.425902Z",
      "activated_on": "2025-07-27T14:01:19.360903Z",
      "vanity_name_servers": [],
      "vanity_name_servers_ips": null,
      "meta": {
        "step": 2,
        "custom_certificate_quota": 0,
        "page_rule_quota": 3,
        "phishing_detected": false
      },
      "owner": {
        "id": null,
        "type": "user",
        "email": null
      },
      "account": {
        "id": "a1",
        "name": "Jo's account"
      },
      "tenant": {
        "id": null,
        "name": null
      },
      "tenant_unit": {
        "id": null
      },
      "permissions": [
        "#worker:edit",
        "#worker:read",
        "#analytics:read",
        "#organization:edit",
        "#zone_settings:read",
        "#zone:read",
        "#organization:read",
        "#dns_records:edit",
        "#dns_records:read",
        "#zone_settings:edit",
        "#r2_bucket_item:read",
        "#r2_bucket_item:edit",
        "#r2_bucket:read",
        "#r2_bucket:edit",
        "#d1:edit",
        "#auditlogs:read",
        "#member:read"
      ],
      "plan": {
        "id": "0feeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
        "name": "Free Website",
        "price": 0,
        "currency": "USD",
        "frequency": "",
        "is_subscribed": false,
        "can_subscribe": false,
        "legacy_id": "free",
        "legacy_discount": false,
        "externally_managed": false
      }
    },
    {
      "id": "z2",
      "name": "jo.blog",
      "status": "active",
      "paused": false,
      "type": "full",
      "development_mode": 0,
      "name_servers": [
        "jade.ns.cloudflare.com",
        "mark.ns.cloudflare.com"
      ],
      "original_name_servers": null,
      "original_registrar": null,
      "original_dnshost": null,
      "modified_on": "2026-03-12T21:15:52.705324Z",
      "created_on": "2026-03-12T21:15:47.367317Z",
      "activated_on": "2026-03-12T21:15:52.547950Z",
      "vanity_name_servers": [],
      "vanity_name_servers_ips": null,
      "meta": {
        "step": 4,
        "custom_certificate_quota": 0,
        "page_rule_quota": 3,
        "phishing_detected": false
      },
      "owner": {
        "id": null,
        "type": "user",
        "email": null
      },
      "account": {
        "id": "a1",
        "name": "Jo's account"
      },
      "tenant": {
        "id": null,
        "name": null
      },
      "tenant_unit": {
        "id": null
      },
      "permissions": [
        "#worker:edit",
        "#worker:read",
        "#analytics:read",
        "#organization:edit",
        "#zone_settings:read",
        "#zone:read",
        "#organization:read",
        "#dns_records:edit",
        "#dns_records:read",
        "#zone_settings:edit",
        "#r2_bucket_item:read",
        "#r2_bucket_item:edit",
        "#r2_bucket:read",
        "#r2_bucket:edit",
        "#d1:edit",
        "#auditlogs:read",
        "#member:read"
      ],
      "plan": {
        "id": "0feeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
        "name": "Free Website",
        "price": 0,
        "currency": "USD",
        "frequency": "",
        "is_subscribed": false,
        "can_subscribe": false,
        "legacy_id": "free",
        "legacy_discount": false,
        "externally_managed": false
      }
    }
  ],
  "result_info": {
    "page": 1,
    "per_page": 20,
    "total_pages": 1,
    "count": 2,
    "total_count": 2
  },
  "success": true,
  "errors": [],
  "messages": []
}
"""

    /** GET /accounts/a1/r2/buckets on an account that hasn't turned R2 on, HTTP 403. */
    const val R2_OFF = """
{"success":false,"errors":[{"code":10042,"message":"Please enable R2 through the Cloudflare Dashboard."}],"messages":[],"result":null}
"""

    /** GET /accounts/a1/workers/subdomain on an account that has no workers.dev subdomain, HTTP 404. */
    const val NO_SUBDOMAIN = """
{"result":null,"success":false,"errors":[{"code":10007,"message":"You do not have a workers.dev subdomain. Please go to https://dash.cloudflare.com/?to=/:account/workers/workers-and-pages. Opening the Workers & Pages landing page for the first time will create a workers.dev subdomain automatically."}],"messages":[]}
"""

    /** GET /accounts/a1/workers/scripts/<none>/subdomain, HTTP 404: the shape of every refusal. */
    const val NO_SUCH_WORKER = """
{
  "result": null,
  "success": false,
  "errors": [
    {
      "code": 10007,
      "message": "This Worker does not exist on your account."
    }
  ],
  "messages": []
}
"""
}
