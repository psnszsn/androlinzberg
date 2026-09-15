#!/usr/bin/env python3
"""Verify stock AudioTrack PCM after the hosted service and private PipeWire sink."""
import argparse,array,json,os,pathlib,signal,subprocess,tempfile,time
from audio_capture_analysis import verify

SERVER_CONFIG='''context.properties = { core.daemon = true core.name = pipewire-0 default.clock.rate = 48000 default.clock.allowed-rates = [ 48000 ] default.clock.quantum = 256 }
context.spa-libs = { audio.convert.* = audioconvert/libspa-audioconvert support.* = support/libspa-support }
context.modules = [
 { name = libpipewire-module-protocol-native }
 { name = libpipewire-module-metadata }
 { name = libpipewire-module-spa-node-factory }
 { name = libpipewire-module-client-node }
 { name = libpipewire-module-adapter }
 { name = libpipewire-module-link-factory }
 { name = libpipewire-module-access args = { access.force = unrestricted } }
]
context.objects = [
 { factory = spa-node-factory args = { factory.name = support.node.driver node.name = Dummy-Driver priority.driver = 20000 } }
 { factory = adapter args = { factory.name = support.null-audio-sink node.name = azk-test-sink media.class = Audio/Sink audio.position = [ FL FR ] adapter.auto-port-config = { mode = dsp monitor = true position = preserve } } }
]
'''
CLIENT_CONFIG='''context.spa-libs = { audio.convert.* = audioconvert/libspa-audioconvert support.* = support/libspa-support }
context.modules = [ { name = libpipewire-module-protocol-native } { name = libpipewire-module-client-node } { name = libpipewire-module-adapter } { name = libpipewire-module-metadata } ]
stream.properties = { adapter.auto-port-config = { mode = dsp } }
'''

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',required=True,type=pathlib.Path)
    parser.add_argument('command',nargs=argparse.REMAINDER)
    args=parser.parse_args(); command=args.command
    if command[:1]==['--']:command=command[1:]
    if not command:parser.error('application command is required')
    output=args.output.resolve();output.mkdir(parents=True,exist_ok=True)
    gate=output/'play.gate';gate.unlink(missing_ok=True)
    result={'passed':False}
    with tempfile.TemporaryDirectory(prefix='azk-audio-') as runtime:
        root=pathlib.Path(runtime)
        (root/'server.conf').write_text(SERVER_CONFIG)
        (root/'client.conf').write_text(CLIENT_CONFIG)
        env=dict(os.environ,XDG_RUNTIME_DIR=runtime,PIPEWIRE_RUNTIME_DIR=runtime,
                 PIPEWIRE_REMOTE='pipewire-0',PIPEWIRE_CONFIG_DIR=runtime,
                 WAYLAND_DISPLAY='azk-hermetic-none',AZK_AUDIO_TEST_GATE=str(gate),
                 AZK_AUDIO_TEST_DIAGNOSTICS=str(output/'diagnostics.json'))
        children=[];logs=[]
        def spawn(argv,name):
            log=open(output/name,'w');logs.append(log)
            process=subprocess.Popen(argv,env=env,stdout=log,stderr=log,start_new_session=True)
            children.append(process);return process
        def wait_for(check,description,timeout=15):
            deadline=time.monotonic()+timeout
            while not check():
                assert time.monotonic()<deadline,'Timed out waiting for '+description
                assert all(p.poll() is None for p in children),'Process exited while waiting for '+description
                time.sleep(.03)
        def ports(direction):
            return subprocess.check_output(['pw-link',direction],env=env,text=True,timeout=3).splitlines()
        def link(source,destination):
            subprocess.run(['pw-link',source,destination],env=env,check=True,capture_output=True,timeout=3)
        try:
            spawn(['pipewire','-c',str(root/'server.conf')],'pipewire.log')
            wait_for(lambda:(root/'pipewire-0').exists(),'private PipeWire server')
            recorder=spawn(['pw-record','--target=0','--rate=48000','--channels=2','--format=f32','--raw',
                            '-P','{ node.name = azk-test-capture adapter.auto-port-config = { mode = dsp } }',
                            str(output/'capture.f32')],'capture.log')
            wait_for(lambda:len([p for p in ports('-i') if p.startswith('azk-test-capture:')])==2,'capture ports')
            application=spawn(command,'app.log')
            wait_for(lambda:'StockAudioDeliverySmoke: READY' in (output/'app.log').read_text(errors='replace'),'AudioTrack readiness',30)
            wait_for(lambda:len([p for p in ports('-o') if p.startswith('artzig-audio:')])==2,'audio service output ports')
            graph=json.loads(subprocess.check_output(['pw-dump'],env=env,timeout=5,stderr=logs[0]))
            (output/'graph.json').write_text(json.dumps(graph,indent=2)+'\n')
            nodes=[o for o in graph if o['type'].endswith(':Node') and o.get('info',{}).get('props',{}).get('node.name')=='artzig-audio']
            assert len(nodes)==1,'Expected one audio service stream'
            client_id=int(nodes[0]['info']['props']['client.id'])
            client=next(o for o in graph if o['id']==client_id)
            audio_pid=int(client['info']['props']['application.process.id'])
            assert audio_pid!=application.pid,'PipeWire playback must live in the audio service process'
            result.update(application_pid=application.pid,audio_service_pid=audio_pid)
            sources=[p for p in ports('-o') if p.startswith('artzig-audio:')]
            assert sources==['artzig-audio:output_1','artzig-audio:output_2'],sources
            for channel in ('FL','FR'):link(f'azk-test-sink:monitor_{channel}',f'azk-test-capture:input_{channel}')
            for source,channel in zip(sources,('FL','FR')):link(source,f'azk-test-sink:playback_{channel}')
            (output/'links.txt').write_text(subprocess.check_output(['pw-link','-l'],env=env,text=True,timeout=3))
            gate.touch()
            assert application.wait(timeout=20)==0,'AudioTrack application failed'
            assert 'StockAudioDeliverySmoke: PASS' in (output/'app.log').read_text(errors='replace')
            time.sleep(.15)
            assert recorder.poll() is None,'PipeWire recorder exited before requested shutdown'
            recorder.send_signal(signal.SIGINT)
            # pw-cat 1.6.2 returns 1 after its signal handler quits the loop;
            # only a playback drain sets EXIT_SUCCESS. Validate the complete
            # captured PCM below, independently of this shutdown convention.
            # https://github.com/PipeWire/pipewire/blob/1.6.2/src/tools/pw-cat.c
            recorder_status=recorder.wait(timeout=3)
            assert recorder_status in (0,1),f'PipeWire recorder terminated abnormally: {recorder_status}'
            result['recorder_exit_after_requested_stop']=recorder_status
            samples=array.array('f');samples.frombytes((output/'capture.f32').read_bytes())
            diagnostics=json.loads((output/'diagnostics.json').read_text())
            assert not diagnostics['issues'],'AudioTrack produced structured runtime issues'
            signal_frames=sum(max(abs(samples[n]),abs(samples[n+1]))>1e-6 for n in range(0,len(samples),2))
            assert signal_frames>=96000,f'Truncated PCM sequence: {signal_frames} non-silent frames'
            result.update(verify(samples),passed=True,non_silent_frames=signal_frames,structured_runtime_issues=0)
            print('stock-audio-delivery: PASS',json.dumps(result),flush=True)
        except BaseException as failure:
            result['error']=str(failure)
            raise
        finally:
            (output/'result.json').write_text(json.dumps(result,indent=2)+'\n')
            for process in reversed(children):
                if process.poll() is None:
                    os.killpg(process.pid,signal.SIGTERM)
                    try:process.wait(timeout=3)
                    except subprocess.TimeoutExpired:os.killpg(process.pid,signal.SIGKILL);process.wait()
            for log in logs:log.close()

if __name__=='__main__':main()
