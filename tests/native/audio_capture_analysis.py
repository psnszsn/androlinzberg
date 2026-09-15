import array,math
PERIOD=1024

def pattern(channel):
    state=0x1234567 if channel==0 else 0x7654321
    result=[]
    for _ in range(PERIOD):
        state=(state*1664525+1013904223)&0xffffffff
        result.append(((state>>16)-32768)/262144)
    return result

def verify(samples, expected_frames=98304):
    assert len(samples)%2==0,'Incomplete stereo frame'
    frames=len(samples)//2
    first=next((n for n in range(frames) if max(abs(samples[2*n]),abs(samples[2*n+1]))>1e-6),None)
    assert first is not None,'PipeWire capture is silent'
    last=next(n for n in range(frames-1,-1,-1) if max(abs(samples[2*n]),abs(samples[2*n+1]))>1e-6)
    assert last-first+1==expected_frames,f'PCM sequence length {last-first+1}, expected {expected_frames}'
    start=first+4096
    assert frames>=start+8192,'Insufficient captured audio after startup'
    reference=[pattern(0),pattern(1)]
    # Match phase independently of startup latency and the platform mixer volume.
    observations=[samples[2*(start+n*17)] for n in range(64)]
    def score(phase):
        expected=[reference[0][(phase+n*17)%PERIOD] for n in range(64)]
        return sum(x*y for x,y in zip(expected,observations))/math.sqrt(sum(x*x for x in expected))
    phase=max(range(PERIOD),key=score)
    phase=(phase-4096)%PERIOD
    channels=[]
    for channel in range(2):
        expected=[reference[channel][(phase+n)%PERIOD] for n in range(expected_frames)]
        actual=[samples[2*(first+n)+channel] for n in range(expected_frames)]
        power=sum(x*x for x in expected)
        gain=sum(x*y for x,y in zip(expected,actual))/power
        assert 1e-3<gain<=1.001,f'Channel {channel} has invalid gain {gain}'
        residual=math.sqrt(sum((y-gain*x)**2 for x,y in zip(expected,actual))/power)/gain
        assert residual<1e-4,f'Channel {channel} PCM mismatch: relative RMS error {residual}'
        channels.append({'gain':gain,'relative_rms_error':residual})
    assert abs(channels[0]['gain']-channels[1]['gain'])<1e-3,'Channel gain mismatch'
    return {'captured_frames':frames,'verified_frames':expected_frames,'phase':phase,'channels':channels}

if __name__=='__main__':
    refs=[pattern(0),pattern(1)]
    good=array.array('f',[0.0]*512)
    for n in range(16384):good.extend([refs[0][n%PERIOD]*.5,refs[1][n%PERIOD]*.5])
    print(verify(good,16384))
    for mode in ('silence','swapped','corrupt'):
        wrong=array.array('f',good)
        if mode=='silence':wrong=array.array('f',[0.0])*len(good)
        elif mode=='swapped':
            for i in range(0,len(wrong),2):wrong[i],wrong[i+1]=wrong[i+1],wrong[i]
        else:
            for i in range(0,len(wrong),31):wrong[i]+=.01
        try:verify(wrong,16384)
        except AssertionError:print('rejected',mode)
        else:raise AssertionError('accepted '+mode)
