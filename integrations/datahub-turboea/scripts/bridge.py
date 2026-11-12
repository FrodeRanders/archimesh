#!/usr/bin/env python3
"""Use the authenticated identity owner without an Archi model or database access."""
import argparse
import json
import os
from pathlib import Path
import sys

sys.path.insert(0,str(Path(__file__).resolve().parents[1] / "src"))
from datahub_turboea.owner import OwnerClient,OwnerError,SourceRef


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action",choices=["observe","lookup","reserve"])
    parser.add_argument("--owner",required=True)
    parser.add_argument("--source",required=True)
    parser.add_argument("--native-id",required=True)
    parser.add_argument("--type",required=True)
    parser.add_argument("--correlation",help="Retained UUID required for reserve; never silently generated on retry")
    parser.add_argument("--token-env",default="FEDERATION_OWNER_TOKEN")
    parser.add_argument("--bootstrap-user",help="Explicit loopback-only development identity")
    parser.add_argument("--bootstrap-role",default="federation_bridge")
    args = parser.parse_args()
    if args.action == "reserve" and not args.correlation: parser.error("reserve requires an explicit retained --correlation UUID")
    if args.bootstrap_user:
        headers = lambda:{"X-Archimesh-User":args.bootstrap_user,"X-Archimesh-Roles":args.bootstrap_role}
    else:
        if not os.environ.get(args.token_env): parser.error("owner token environment variable is missing")
        headers = lambda:{"Authorization":"Bearer " + os.environ[args.token_env]}
    client = OwnerClient(args.owner,headers,bootstrap=bool(args.bootstrap_user))
    source = SourceRef(args.source,args.native_id,args.type)
    result = client.reserve_source(source,args.correlation) if args.action == "reserve" else getattr(client,args.action)(source)
    print(json.dumps(result,indent=2,ensure_ascii=False))


if __name__ == "__main__":
    try: main()
    except (OwnerError,ValueError) as error:
        print(str(error),file=sys.stderr)
        sys.exit(1)
