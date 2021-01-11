#![recursion_limit = "1024"]
use cairo;
use ngspice::{Callbacks, NgSpice, NgSpiceError, Simulator};
use std::collections::HashMap;
use vgtk::ext::*;
use vgtk::lib::gdk_pixbuf::Pixbuf;
//use vgtk::lib::gdk::EventMask;
use vgtk::lib::gio::{ApplicationFlags, Cancellable, MemoryInputStream, SeekableExt};
use vgtk::lib::glib::{SeekType, Bytes};
use vgtk::lib::gtk::*;
use vgtk::{gtk, run, Component, UpdateAction, VNode};
//use cairo::{FontSlant, FontWeight};
//use std::f64::consts::PI;

static NMOS: &[u8] = include_bytes!("img/nmos.svg");
static OPEN: &[u8] = include_bytes!("img/open.svg");
static V: &[u8] = include_bytes!("img/v.svg");
static I: &[u8] = include_bytes!("img/i.svg");
static R: &[u8] = include_bytes!("img/r.svg");

#[derive(Clone, Debug)]
struct Cb {}

impl Callbacks for Cb {
    fn send_char(&mut self, s: &str) {
        print!("{}\n", s);
    }
}

#[derive(Clone)]
struct Model {
    nmos: Pixbuf,
    open_small: Pixbuf,
    v: Pixbuf,
    i_small: Pixbuf,
    r: Pixbuf,
    r_small: Pixbuf,
    params: HashMap<String, String>,
    results: HashMap<String, f64>,
    spice: std::sync::Arc<NgSpice<Cb>>,
}

impl Default for Model {
    fn default() -> Model {
        let data_stream = MemoryInputStream::from_bytes(&Bytes::from_static(NMOS));
        let nmos = Pixbuf::from_stream(&data_stream, None as Option<&Cancellable>).unwrap();
        let data_stream = MemoryInputStream::from_bytes(&Bytes::from_static(OPEN));
        let open_small = Pixbuf::from_stream_at_scale(&data_stream, 40, 40, true, None as Option<&Cancellable>).unwrap();
        let data_stream = MemoryInputStream::from_bytes(&Bytes::from_static(V));
        let v = Pixbuf::from_stream(&data_stream, None as Option<&Cancellable>).unwrap();
        let data_stream = MemoryInputStream::from_bytes(&Bytes::from_static(I));
        let i_small = Pixbuf::from_stream_at_scale(&data_stream, 40, 40, true, None as Option<&Cancellable>).unwrap();
        let data_stream = MemoryInputStream::from_bytes(&Bytes::from_static(R));
        let r = Pixbuf::from_stream(&data_stream, None as Option<&Cancellable>).unwrap();
        data_stream.seek(0, SeekType::Set, None as Option<&Cancellable>).unwrap();
        let r_small = Pixbuf::from_stream_at_scale(&data_stream, 40, 40, true, None as Option<&Cancellable>).unwrap();
        let mut params = HashMap::new();
        params.insert(String::from("v1 dc"), String::from("5"));
        params.insert(String::from("v2 dc"), String::from("2"));
        params.insert(String::from("r1"), String::from("1k"));
        params.insert(String::from("m1 w"), String::from("10u"));
        params.insert(String::from("m1 l"), String::from("1u"));

        let spice = NgSpice::new(Cb {}).unwrap();
        spice.circuit(&[
                ".title my awesome schematic",
                ".MODEL FAKE_NMOS NMOS (LEVEL=3 VTO=0.75)",
                ".save all @m1[gm] @m1[id] @m1[vgs] @m1[vds] @m1[vto]",
                "R1 /vdd /drain 10k",
                "M1 /drain /gate GND GND FAKE_NMOS W=10u L=1u",
                "V1 /vdd GND dc(5)",
                "V2 /gate GND dc(2)",
                ".end",
            ]).expect("circuit failed");
        let results = spice.op().expect("op failed");
        let results = results.data.iter().map(|(k, v)| {
            if let ngspice::ComplexSlice::Real(num) = v.data {
                (k.clone(), num.first().unwrap_or(&0.0).clone())
            } else {
                (k.clone(), 0.0)
            }
        }
        ).collect();
        Model {
            nmos: nmos,
            open_small: open_small,
            v: v,
            i_small: i_small,
            r: r,
            r_small: r_small,
            params: params,
            results: results,
            spice: spice,
        }
    }
}

#[derive(Clone, Debug)]
enum Message {
    Exit,
    ParamChange(String, String),
    Coord(i64),
    None,
}

impl IntoSignalReturn<Inhibit> for Message {
    fn into_signal_return(&self) -> Inhibit {
        Inhibit(false)
    }
}

fn draw_layout( _l: &Layout, cr: &cairo::Context) {
    cr.set_line_width(1.5);
    cr.set_source_rgb(0., 0., 0.);
    cr.move_to(45., 400.);
    cr.line_to(45., 245.);
    cr.move_to(45., 205.);
    cr.line_to(45., 40.);
    cr.line_to(310., 40.);
    cr.line_to(310., 60.);
    cr.move_to(310., 140.);
    cr.line_to(310., 220.);
    cr.move_to(310., 340.);
    cr.line_to(310., 400.);
    cr.line_to(45., 400.);
    cr.move_to(125., 400.);
    cr.line_to(125., 360.);
    cr.move_to(125., 330.);
    cr.line_to(125., 300.);
    cr.line_to(245., 300.);
    cr.stroke();
    //Inhibit(false)
}

impl Component for Model {
    type Message = Message;
    type Properties = ();

    fn update(&mut self, msg: Self::Message) -> UpdateAction<Self> {
        match msg {
            Message::Exit => {
                vgtk::quit();
                UpdateAction::None
            }
            Message::ParamChange(key, val) => {
                let cmd = format!("alter {}={}", &key, &val);
                self.params.insert(key, val);
                if self.spice.command(&cmd).is_err() {
                    return UpdateAction::None;
                }
                if let Ok(results) = self.spice.op() {
                    self.results = results.data.iter().map(|(k, v)| {
                        if let ngspice::ComplexSlice::Real(num) = v.data {
                            (k.clone(), num.first().unwrap_or(&0.0).clone())
                        } else {
                            (k.clone(), 0.0)
                        }
                    }
                    ).collect();
                    return UpdateAction::Render;
                }
                UpdateAction::None
            }
            _ => UpdateAction::None,
        }
    }

    fn view(&self) -> VNode<Model> {
        let vgs = *self.results.get("@m1[vgs]").unwrap_or(&0.);
        let vds = *self.results.get("@m1[vds]").unwrap_or(&0.);
        let vth = 0.75;
        gtk! {
            <Application::new_unwrap(Some("nl.pepijndevos.mosaic"), ApplicationFlags::empty())>
                <Window default_width=500 default_height=500
                        border_width=20 on destroy=|_| Message::Exit>
                    <Layout on draw=|l, cr| { draw_layout(l, cr); Message::None }
                            /*on motion_notify_event=|l, e| Message::Coord(e.get_coords().unwrap())*/>
                        <Image Layout::x=220 Layout::y=200 pixbuf=Some(self.nmos.clone())/>
                        <Image Layout::x=240 Layout::y=220
                        pixbuf={ if vgs < vth {
                            Some(self.open_small.clone())
                        } else if vds < vgs-vth {
                            Some(self.r_small.clone())
                        } else {
                            Some(self.i_small.clone())
                        } }/>
                        <Entry Layout::x=350 Layout::y=220 width_chars=4
                               text={self.params["m1 w"].clone()}
                               on changed=|e| Message::ParamChange(
                                   String::from("m1 w"),
                                   String::from(e.get_text())) />
                        <Entry Layout::x=350 Layout::y=260 width_chars=4
                               text={self.params["m1 l"].clone()}
                               on changed=|e| Message::ParamChange(
                                   String::from("m1 l"),
                                   String::from(e.get_text())) />
                        <Label Layout::x=350 Layout::y=300
                               text=format!("id={:.3e}", *self.results.get("@m1[id]").unwrap_or(&0.))/>
                        <Label Layout::x=350 Layout::y=320
                               text=format!("gm={:.3e}", *self.results.get("@m1[gm]").unwrap_or(&0.))/>
                        <Image Layout::x=300 Layout::y=50 pixbuf=Some(self.r.clone())/>
                        <Entry Layout::x=350 Layout::y=75 width_chars=4
                               text={self.params["r1"].clone()}
                               on changed=|e| Message::ParamChange(
                                   String::from("r1"),
                                   String::from(e.get_text())) />
                        <Image Layout::x=20 Layout::y=200 pixbuf=Some(self.v.clone())/>
                        <Entry Layout::x=70 Layout::y=210 width_chars=4
                               text={self.params["v1 dc"].clone()}
                               on changed=|e| Message::ParamChange(
                                   String::from("v1 dc"),
                                   String::from(e.get_text())) />
                        <Image Layout::x=100 Layout::y=320 pixbuf=Some(self.v.clone())/>
                        <Entry Layout::x=150 Layout::y=330 width_chars=4
                               text={self.params["v2 dc"].clone()}
                               on changed=|e| Message::ParamChange(
                                   String::from("v2 dc"),
                                   String::from(e.get_text())) />
                    </Layout>
                </Window>
            </Application>
        }
    }
}

fn main() {
    pretty_env_logger::init();
    std::process::exit(run::<Model>());
}
