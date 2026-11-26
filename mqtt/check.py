#!/usr/bin/env python3
"""Request a foreground phone self-test. No personal text or audio is requested."""
import argparse
import json
import threading
import time
import uuid
from pathlib import Path
import paho.mqtt.client as mqtt

ROOT='translator/v1/phone1'
ALLOWED={'background_service','app_visible','voice_target','request_id','version','timestamp','status','tts_ready','offline_voices_for_target','microphone_tested','installed_models','translation_ms','result_nonempty'}

def main():
    p=argparse.ArgumentParser()
    p.add_argument('--credentials',type=Path,default=Path.home()/'.config/translator-mqtt/credentials.json')
    p.add_argument('--external',action='store_true',help='Use TLS WebSocket instead of localhost')
    p.add_argument('--timeout',type=int,default=35)
    args=p.parse_args()
    creds=json.loads(args.credentials.read_text())
    request=uuid.uuid4().hex; done=threading.Event(); response=[]; failures=[]
    client=mqtt.Client(mqtt.CallbackAPIVersion.VERSION2,client_id='translator-check-'+uuid.uuid4().hex,transport='websockets' if args.external else 'tcp')
    client.username_pw_set('translator-monitor',creds['translator-monitor'])
    if args.external: client.ws_set_options(path='/mqtt'); client.tls_set()
    def connected(c,u,flags,reason,properties):
        if reason.is_failure: failures.append('authentication_failed');done.set();return
        c.subscribe(ROOT+'/reply',qos=1)
    def subscribed(c,u,mid,reasons,properties):
        if any(r.is_failure for r in reasons): failures.append('subscription_denied');done.set();return
        c.publish(ROOT+'/cmd',json.dumps({'action':'self_test','request_id':request,'issued_at':int(time.time())}),qos=1,retain=False)
    def received(c,u,msg):
        if msg.retain or len(msg.payload)>8192: return
        try:
            report=json.loads(msg.payload)
            if report.get('request_id')!=request: return
            response.append({k:v for k,v in report.items() if k in ALLOWED});done.set()
        except (ValueError,AttributeError): pass
    client.on_connect=connected;client.on_subscribe=subscribed;client.on_message=received
    try:
        client.connect('mqtt.velu-vara.com' if args.external else '127.0.0.1',443 if args.external else 1883,keepalive=30)
        client.loop_start();done.wait(args.timeout)
    finally:
        client.disconnect();client.loop_stop()
    if response: print(json.dumps(response[0],ensure_ascii=False));return 0
    print(json.dumps({'status':failures[0] if failures else 'no_response','meaning':'No reply does not prove application failure; open the app and enable MQTT.'}))
    return 2

if __name__=='__main__': raise SystemExit(main())
