#!/usr/bin/env python3
import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/"src"))
from datahub_turboea.worker import main

if __name__=="__main__":
    try: sys.exit(main())
    except (ValueError,KeyError,OSError):
        print("Invalid bridge configuration or unavailable credential",file=sys.stderr)
        sys.exit(1)
