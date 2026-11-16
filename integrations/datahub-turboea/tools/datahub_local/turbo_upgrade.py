"""Disposable Turbo EA database migration trial; no ArchiMesh/model dependencies."""
from contextlib import contextmanager
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import uuid

from .harness import free_port, private_write
from .probe import require, wait_for

INVENTORY_SQL = """SELECT json_build_object(
 'cards', (SELECT coalesce(json_agg(r ORDER BY r.id), '[]') FROM
   (SELECT id,type,name,description,attributes,lifecycle,status,parent_id,external_id,subtype FROM cards) r),
 'relations', (SELECT coalesce(json_agg(r ORDER BY r.id), '[]') FROM
   (SELECT id,type,source_id,target_id,attributes,description FROM relations) r),
 'cardTypes', (SELECT coalesce(json_agg(r ORDER BY r.id), '[]') FROM (SELECT id,key FROM card_types) r),
 'relationTypes', (SELECT coalesce(json_agg(r ORDER BY r.id), '[]') FROM (SELECT id,key FROM relation_types) r)
)"""


def container(value):
    require(bool(re.fullmatch(r"[0-9a-f]{12,64}", value)), "exact Docker container identifier")
    return value


def inventory(container_id):
    result=subprocess.run(["docker","exec",container(container_id),"psql","-U","turboea","-d","turboea","-tA","-c",INVENTORY_SQL],
                          capture_output=True,text=True,timeout=60)
    require(result.returncode==0,"Turbo EA identity inventory read")
    return json.loads(result.stdout)


def database_backup(container_id,path):
    """Consistent pg_dump snapshot; binary backup and diagnostics stay private."""
    with open(path,"xb",opener=lambda p,flags:os.open(p,flags,0o600)) as output:
        result=subprocess.run(["docker","exec",container(container_id),"pg_dump","-U","turboea","-d","turboea","--format=custom"],
                              stdout=output,stderr=subprocess.PIPE,timeout=300)
    require(result.returncode==0 and Path(path).stat().st_size>0,"Turbo EA database backup; original database unchanged")


def files_backup(container_id,path):
    with open(path,"xb",opener=lambda p,flags:os.open(p,flags,0o600)) as output:
        result=subprocess.run(["docker","cp",container(container_id)+":/app/data/.","-"],
                              stdout=output,stderr=subprocess.PIPE,timeout=300)
    require(result.returncode==0,"Turbo EA attachment backup")


def configuration(db_image,backend_image,secret):
    port=free_port(); password=secrets.token_hex(24)
    return port,{"services":{
        "db":{"image":db_image,"environment":{"POSTGRES_DB":"turboea","POSTGRES_USER":"turboea", "POSTGRES_PASSWORD":password,"PGDATA":"/var/lib/postgresql/data"},
              "volumes":["trial_data:/var/lib/postgresql/data"]},
        "backend":{"image":backend_image,"environment":{"POSTGRES_HOST":"db","POSTGRES_PORT":"5432","POSTGRES_DB":"turboea","POSTGRES_USER":"turboea",
                  "POSTGRES_PASSWORD":password,"SECRET_KEY":secret,"ENVIRONMENT":"development","SEED_DEMO":"false","RESET_DB":"false","AI_AUTO_CONFIGURE":"false","TRUST_PROXY_HEADERS":"false"},
                   "ports":[f"127.0.0.1:{port}:8000"],"depends_on":["db"]}},"volumes":{"trial_data":{}}}


@contextmanager
def migration_trial(directory,backup,db_image,backend_image,secret):
    """Restore and migrate an isolated clone; clean up only its generated project."""
    directory=Path(directory);directory.mkdir(mode=0o700)
    project="turboea-upgrade-trial-"+uuid.uuid4().hex[:12]
    port,document=configuration(db_image,backend_image,secret)
    path=directory/"compose.json";private_write(path,json.dumps(document))
    private_write(directory/"project.txt",project)
    base=["docker","compose","--project-name",project,"--file",str(path)]
    with open(directory/"trial.log","xb",opener=lambda p,flags:os.open(p,flags,0o600)) as log:
        def run(*args,stdin=None,check=True):
            result=subprocess.run([*base,*args],stdin=stdin,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=300)
            log.write(result.stdout+result.stderr);log.flush()
            if check: require(result.returncode==0,"isolated migration trial command; inspect private trial.log")
            return result
        try:
            run("up","-d","db")
            wait_for(lambda:run("exec","-T","db","pg_isready","-U","turboea",check=False).returncode,lambda code:code==0,"migration clone database",120)
            with open(backup,"rb") as source:
                run("exec","-T","db","pg_restore","--exit-on-error","-U","turboea","-d","turboea",stdin=source)
            db=run("ps","-q","db").stdout.decode().strip()
            run("up","-d","backend")
            yield port,db
        finally:
            run("logs","--no-color","--tail","200",check=False)
            cleanup=run("down","--volumes","--remove-orphans",check=False)
            require(cleanup.returncode==0,"isolated migration trial cleanup; inspect private trial.log")
