const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname,
  '../android/app/src/main/kotlin/com/red/note/PrivateChatActivity.kt'), 'utf8');
const script = source.match(/"""\s*([\s\S]*?)\s*"""\.trimIndent\(\)/)?.[1];
assert.ok(script, 'Private chat DOM script is missing');
for (const host of ['www.xiaohongshu.com', 'www.rednote.com']) {
  let onClick;
  let registrations = 0;
  const location = {origin: `https://${host}`, href: `https://${host}/chat/peer`};
  const style = {};
  const context = {
    URL, location, window: {},
    document: {
      getElementById: () => style,
      addEventListener: (type, callback, capture) => {
        assert.equal(type, 'click');
        assert.equal(capture, true);
        onClick = callback;
        registrations++;
      },
    },
  };
  vm.runInNewContext(script, context);
  vm.runInNewContext(script, context);
  assert.equal(registrations, 1, 'Repeated layout updates must not duplicate listeners');
  for (const href of [
    `${location.origin}/user/profile/peer?xsec_token=a%2Bb%3D&xsec_source=pc_im`,
    `${location.origin}/explore/note`,
    'https://evil.example/user/profile/peer',
  ]) {
    let prevented = false;
    let stopped = false;
    const previous = location.href;
    onClick({target: {closest: () => ({href})},
      preventDefault: () => { prevented = true; },
      stopImmediatePropagation: () => { stopped = true; }});
    const isProfile = href.startsWith(`${location.origin}/user/profile/`);
    assert.equal(prevented, isProfile);
    assert.equal(stopped, isProfile);
    assert.equal(location.href, isProfile ? href : previous);
  }
}
console.log('Private chat profile links: both sites, references, click interception and deduplication passed');

const loginSource = fs.readFileSync(path.join(__dirname,
  '../android/app/src/main/kotlin/com/red/note/LoginActivity.kt'), 'utf8');
const tapScript = loginSource.match(/val noTapHighlightScript = """([\s\S]*?)"""\.trimIndent\(\)/)?.[1];
assert.ok(tapScript, 'Shared browser tap style is missing');
const styles = new Map();
const document = {
  getElementById: id => styles.get(id),
  createElement: () => ({}),
  head: {appendChild: element => styles.set(element.id, element)},
};
assert.equal(vm.runInNewContext(tapScript + 'JSON.stringify({w: 960})', {document}), '{"w":960}');
vm.runInNewContext(tapScript, {document});
assert.equal(styles.size, 1);
assert.equal(styles.get('rednote-no-tap-highlight').textContent,
  '*{-webkit-tap-highlight-color:transparent!important}');
console.log('Shared WebView tap style: idempotence and login environment return value passed');
