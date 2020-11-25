#![recursion_limit = "512"]
use vgtk::ext::*;
use vgtk::lib::gtk::*;
use vgtk::{gtk, run, Component, UpdateAction, VNode};
use vgtk::lib::gdk_pixbuf::Pixbuf;
use vgtk::lib::gio::{ApplicationFlags, Cancellable, MemoryInputStream};
use vgtk::lib::glib::Bytes;
use cairo;
use std::collections::HashMap;
//use cairo::{FontSlant, FontWeight};
//use std::f64::consts::PI;

static NMOS: &[u8] = include_bytes!("img/nmos.svg");
static V: &[u8] = include_bytes!("img/v.svg");
static R: &[u8] = include_bytes!("img/r.svg");

#[derive(Clone, Debug)]
struct Model {
    nmos: Pixbuf,
    v: Pixbuf,
    r: Pixbuf,
    params: HashMap<String, String>,
}

impl Default for Model {
    fn default() -> Model {
        let data_stream = MemoryInputStream::from_bytes(&Bytes::from_static(NMOS));
        let nmos = Pixbuf::from_stream(&data_stream, None as Option<&Cancellable>).unwrap();
        let data_stream = MemoryInputStream::from_bytes(&Bytes::from_static(V));
        let v = Pixbuf::from_stream(&data_stream, None as Option<&Cancellable>).unwrap();
        let data_stream = MemoryInputStream::from_bytes(&Bytes::from_static(R));
        let r = Pixbuf::from_stream(&data_stream, None as Option<&Cancellable>).unwrap();
        let mut params = HashMap::new();
        params.insert(String::from("vdd"), String::from("5"));
        params.insert(String::from("vg"), String::from("2"));
        params.insert(String::from("r"), String::from("1k"));
        params.insert(String::from("w"), String::from("10u"));
        params.insert(String::from("l"), String::from("1u"));
        Model {
            nmos: nmos,
            v: v,
            r: r,
            params: params,
        }
    }
}

#[derive(Clone, Debug)]
enum Message {
    Exit,
    ParamChange(String, String),
    None,
}

fn draw_layout(_l: &Layout, _cr: &cairo::Context) -> Inhibit {
    Inhibit(false)
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
                self.params.insert(key, val);
                UpdateAction::Render
            }
            _ => UpdateAction::None,
        }
    }

    fn view(&self) -> VNode<Model> {
        gtk! {
            <Application::new_unwrap(Some("nl.pepijndevos.mosaic"), ApplicationFlags::empty())>
                <Window border_width=20 default_width=500 default_height=500 on destroy=|_| Message::Exit>
                    <Layout on realize=|l| { l.connect_draw(draw_layout); Message::None }>
                        <Image Layout::x=220 Layout::y=200 pixbuf=Some(self.nmos.clone())/>
                        <Entry Layout::x=350 Layout::y=200 width_chars=4
                               text={self.params["w"].clone()}
                               on changed=|e| Message::ParamChange(
                                   String::from("w"),
                                   String::from(e.get_text())) />
                        <Entry Layout::x=350 Layout::y=250 width_chars=4
                               text={self.params["l"].clone()}
                               on changed=|e| Message::ParamChange(
                                   String::from("l"),
                                   String::from(e.get_text())) />
                        <Image Layout::x=300 Layout::y=50 pixbuf=Some(self.r.clone())/>
                        <Entry Layout::x=350 Layout::y=70 width_chars=4
                               text={self.params["r"].clone()}
                               on changed=|e| Message::ParamChange(
                                   String::from("r"),
                                   String::from(e.get_text())) />
                        <Image Layout::x=20 Layout::y=200 pixbuf=Some(self.v.clone())/>
                        <Entry Layout::x=70 Layout::y=200 width_chars=4
                               text={self.params["vdd"].clone()}
                               on changed=|e| Message::ParamChange(
                                   String::from("vdd"),
                                   String::from(e.get_text())) />
                        <Image Layout::x=100 Layout::y=320 pixbuf=Some(self.v.clone())/>
                        <Entry Layout::x=150 Layout::y=320 width_chars=4
                               text={self.params["vg"].clone()}
                               on changed=|e| Message::ParamChange(
                                   String::from("vg"),
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
