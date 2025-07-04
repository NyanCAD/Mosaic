# SPDX-FileCopyrightText: 2022 Pepijn de Vos
#
# SPDX-License-Identifier: MPL-2.0

import holoviews as hv
import numpy as np
import pandas as pd
import functools
from holoviews.streams import Buffer, Stream, param
from holoviews.operation.downsample import downsample1d
from bokeh.models import WheelZoomTool

try:
    import scipy.fft
    import scipy.interpolate
except ImportError:
    print("scipy not available, fft plots will not work")


hv.extension('bokeh')

active_traces = Stream.define('traces', cols=[])

def configure_tools(plot, element):
    for t in plot.state.toolbar.tools:
        if isinstance(t, WheelZoomTool):
            t.dimensions = 'width'

def _timeplot(data, cols=None):
    if cols is None:
        cols = data.columns.tolist()
    traces = {k: hv.Curve(data, 'index', k).redim(**{k:'amplitude'}) for k in cols}
    if not cols: # add dummy trace to avoid error
        traces = {"dummy": hv.Scatter([])}
    return hv.NdOverlay(traces, kdims='k')

def timeplot(data, cols=None):
    return downsample1d(_timeplot(data, cols)).opts(hooks=[configure_tools])

def live_timeplot(streams):
    curve_dmap = hv.DynamicMap(_timeplot, streams=streams)
    return downsample1d(curve_dmap).opts(hooks=[configure_tools])

def bodeplot(data, cols=None):
    if cols is None:
        cols = data.columns.tolist()
    data[data==0] = np.finfo(float).eps # prevent infinity
    xlim = (0.1, 10) if data.index.empty else (data.index[0], data.index[-1])
    mag_traces = []
    pha_traces = []
    for k in cols:
        mt = hv.Curve((data.index, 20*np.log10(np.abs(data[k]))), 'freqency', 'amplitude (dB)')
        pt = hv.Curve((data.index, np.angle(data[k], deg=True)), 'freqency', 'angle')
        mag_traces.append(mt)
        pha_traces.append(pt)
    
    if not cols:
        mag_traces = [hv.Curve([])]
        pha_traces = [hv.Curve([])]

    mag = hv.Overlay(mag_traces).opts(logx=True, logy=False, xlim=xlim)
    phase = hv.Overlay(pha_traces).opts(logx=True, xlim=xlim)
    return hv.Layout([mag, phase]).cols(1)

def live_bodeplot(streams):
    return hv.DynamicMap(bodeplot, streams=streams)

def sweepplot(data, cols=None):
    if cols is None:
        cols = data.columns.tolist()
    traces = {k: hv.Curve(data, 'index', k).redim(**{k:'amplitude'}) for k in cols}
    if not cols:
        traces = {"dummy": hv.Curve([])}
    return hv.NdOverlay(traces, kdims='k')

def live_sweepplot(streams):
    return hv.DynamicMap(sweepplot, streams=streams)

def table(streams):
    return hv.DynamicMap(
        lambda data, cols: hv.Table(data[cols]),
        streams=streams
    )

def fftplot(data, cols=[], n=1024):
    if cols is None:
        cols = data.columns.tolist()
    traces = []
    for k in cols:
        fftdat = fft(data[k], n).iloc[:n//2]
        mt = hv.Curve((fftdat.index, fftdat.abs()), 'freqency', 'amplitude')
        traces.append(mt)

    if not cols:
        traces = [hv.Curve([])]

    return hv.Overlay(traces).opts(logx=True, logy=True)

def live_fftplot(streams, n=1024):
    return hv.DynamicMap(functools.partial(fftplot, n=n), streams=streams)

## processing

def span(ts):
    return ts.index[-1] - ts.index[0]

def interpolate(ts):
    x = ts.index.to_numpy()
    y = ts.to_numpy()
    return scipy.interpolate.interp1d(x, y)

def sample(ts, n):
    Y = interpolate(ts)
    return Y(np.linspace(ts.index[0], ts.index[-1], n))

def fft(ts, n):
    ft = sample(ts, n)
    dt = span(ts)/n
    fftdat = scipy.fft.fft(ft)
    fftfreq = scipy.fft.fftfreq(ft.size, dt);
    print(ft.size, dt, fftfreq, fftdat)
    return pd.Series(fftdat, fftfreq, name=ts.name)