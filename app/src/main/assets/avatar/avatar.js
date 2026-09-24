/*
 * VRM-powered 3D avatar for ElinaAI.
 *
 * Loads `model.vrm` via three-vrm (Apache-2.0). Drives expressions,
 * lipsync, blinking, and subtle idle motion based on commands from
 * the Kotlin side (ElinaAI.setEmotion / setSpeaking / wave).
 *
 * If model.vrm fails to load (missing or incompatible), falls back to
 * a small procedural placeholder so the UI is never empty.
 */

import * as THREE from 'three';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';
import { VRMLoaderPlugin, VRMUtils } from 'three-vrm';

const stage    = document.getElementById('stage');
const badge    = document.getElementById('badge');
const badgeTx  = document.getElementById('badgeText');

const scene    = new THREE.Scene();
const camera   = new THREE.PerspectiveCamera(28, 1, 0.1, 100);
const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
renderer.setPixelRatio(window.devicePixelRatio);
renderer.setSize(stage.clientWidth, stage.clientHeight);
renderer.outputColorSpace = THREE.SRGBColorSpace;
renderer.toneMapping = THREE.ACESFilmicToneMapping;
renderer.toneMappingExposure = 1.05;
stage.appendChild(renderer.domElement);

// Soft warm museum lighting
scene.add(new THREE.HemisphereLight(0xfff0d7, 0x2c2522, 0.85));
const key = new THREE.DirectionalLight(0xffe9c4, 1.05);
key.position.set(2.5, 4, 3);
scene.add(key);
const rim = new THREE.DirectionalLight(0xb8956a, 0.5);
rim.position.set(-3, 2, -2);
scene.add(rim);

// Subtle stage plate so the model isn't floating in the void
{
    const plate = new THREE.Mesh(
        new THREE.CylinderGeometry(0.55, 0.55, 0.04, 32),
        new THREE.MeshStandardMaterial({ color: 0x3a2d22, roughness: 0.85 }),
    );
    plate.position.y = -0.02;
    scene.add(plate);
}

// ─── Avatar load ────────────────────────────────────────────────
let vrm = null;
let mixer = null;     // reserved for future BVH animation playback
const clock = new THREE.Clock();

const loader = new GLTFLoader();
loader.register((parser) => new VRMLoaderPlugin(parser));

loader.load(
    'model.vrm',
    (gltf) => {
        vrm = gltf.userData.vrm;
        VRMUtils.removeUnnecessaryVertices(gltf.scene);
        VRMUtils.removeUnnecessaryJoints(gltf.scene);

        // VRM 0.x faces +Z; VRM 1.0 faces -Z. Either way we want the avatar
        // to look at the camera, so rotate to face +Z (toward viewer).
        if (vrm.meta && vrm.meta.metaVersion === '0') {
            VRMUtils.rotateVRM0(vrm);
        }
        scene.add(vrm.scene);

        // Frame the upper body. VRM scale is meters; head height ~ 1.45m.
        const head = vrm.humanoid?.getNormalizedBoneNode?.('head');
        const headY = head ? head.getWorldPosition(new THREE.Vector3()).y : 1.45;
        camera.position.set(0, headY - 0.1, 1.7);
        camera.lookAt(0, headY - 0.05, 0);

        // Relax the arms a little (defaults are T-pose). Brings arms down ~30°.
        relaxArms(vrm);

        // Capture rest rotations for the extra bones the idle loop animates below, so
        // per-frame offsets are always additive to the real bind pose (never replacing it).
        torsoBoneName = vrm.humanoid?.getNormalizedBoneNode?.('chest') ? 'chest'
            : (vrm.humanoid?.getNormalizedBoneNode?.('spine') ? 'spine' : null);
        for (const name of ['leftEye', 'rightEye', torsoBoneName].filter(Boolean)) {
            const bone = vrm.humanoid?.getNormalizedBoneNode?.(name);
            if (bone) restPose[name] = bone.rotation.clone();
        }

        // Initial expression
        applyEmotion('idle');

        // Friendly greeting wave shortly after she appears.
        setTimeout(() => { waving = 1.6; }, 700);

        try { window.ElinaAIBridge?.onAvatarReady?.(); } catch (_) {}
    },
    undefined,
    (err) => {
        console.warn('[avatar] failed to load model.vrm:', err);
        try { window.ElinaAIBridge?.onAvatarError?.(String(err)); } catch (_) {}
        // Show a clear visual fallback so the screen isn't blank.
        scene.add(buildFallback());
        camera.position.set(0, 1.45, 3.6);
        camera.lookAt(0, 1.3, 0);
    },
);

function relaxArms(vrm) {
    const armDownDeg = 65;   // 0 = T-pose, 90 = arms straight down
    const r = THREE.MathUtils.degToRad(armDownDeg);
    for (const side of ['left', 'right']) {
        const upper = vrm.humanoid?.getNormalizedBoneNode?.(side + 'UpperArm');
        if (upper) {
            // VRM upper-arm bone: rotate around Z to bring arm down at side
            upper.rotation.z = (side === 'left' ? +1 : -1) * r;
        }
    }
}

function buildFallback() {
    const g = new THREE.Group();
    const mat = new THREE.MeshStandardMaterial({ color: 0xb8956a });
    const body = new THREE.Mesh(new THREE.CapsuleGeometry(0.32, 0.55, 6, 16), mat);
    body.position.y = 0.95;
    g.add(body);
    const head = new THREE.Mesh(new THREE.SphereGeometry(0.28, 32, 32),
        new THREE.MeshStandardMaterial({ color: 0xf2d5b0 }));
    head.position.y = 1.65;
    g.add(head);
    return g;
}

// ─── Expression / emotion mapping ──────────────────────────────
//
// VRM 1.0 standard expressions: happy, sad, angry, surprised, relaxed,
// neutral, blink, blinkLeft, blinkRight, aa, ih, ou, ee, oh, lookUp,
// lookDown, lookLeft, lookRight.
// Not all VRMs implement all of them — apply gracefully.

// Targets set by applyEmotion(); tick() smoothly lerps the real expression values toward
// these every frame instead of snapping, so transitions (including back to neutral) are
// gradual rather than instant.
const expressionTargets = { happy: 0, sad: 0, angry: 0, surprised: 0, relaxed: 0, neutral: 0 };

function applyEmotion(name) {
    Object.keys(expressionTargets).forEach((e) => { expressionTargets[e] = 0; });
    switch (name) {
        case 'idle':
            expressionTargets.neutral = 0.5;
            expressionTargets.relaxed = 0.15;
            break;
        case 'listening':
            expressionTargets.happy = 0.3;
            break;
        case 'thinking':
            expressionTargets.relaxed = 0.4;
            break;
        case 'speaking':
            expressionTargets.happy = 0.22;
            break;
        case 'happy':
            expressionTargets.happy = 0.7;
            break;
        case 'neutral':
            expressionTargets.neutral = 0.5;
            break;
        case 'surprised':
            expressionTargets.surprised = 0.75;
            break;
        case 'sad':
            expressionTargets.sad = 0.5;
            break;
        case 'error':
            expressionTargets.sad = 0.55;
            break;
    }
}

// ─── State machine ────────────────────────────────────────────────
let state    = 'idle';
let speaking = false;
let waving   = 0;
let mouthLevel = 0;
let blinkPhase = 0;
let blinkStrength = 0;
let blinkDuration = 0.12;
let nextBlinkAt = 2.0 + Math.random() * 2.5;
let gazeTimer = 0;
let gazeX = 0, gazeY = 0;

// Phase 4A idle-life additions: independent eye micro-saccades and subtle torso sway.
// restPose captures each bone's bind rotation once at load so additive offsets below can
// never drift the character away from its rest pose.
const restPose = {};
let torsoBoneName = null;
let eyeSaccadeTimer = 0;
let eyeTargetX = 0, eyeTargetY = 0;
let eyeX = 0, eyeY = 0;
const idlePhaseOffset = Math.random() * Math.PI * 2;

// Phase 4C gesture state: one-shot, self-decaying — always returns to 0 on its own, so a
// cancelled/interrupted turn can never leave a gesture stuck mid-motion.
const NOD_DURATION = 0.5;
const SHAKE_DURATION = 0.45;
let nodPhase = 0;
let shakePhase = 0;

window.ElinaAI = {
    setEmotion(name) {
        const prev = state;
        state = name;
        badge.className = name;
        badgeTx.textContent = ({
            idle: 'Ready', listening: 'Listening', thinking: 'Thinking', speaking: 'Speaking', error: 'Error',
        })[name] || name;
        applyEmotion(name);
        if (name === 'speaking' && prev !== 'speaking') nodPhase = NOD_DURATION;
        if (name === 'error' && prev !== 'error') shakePhase = SHAKE_DURATION;
    },
    setSpeaking(active) {
        const was = speaking;
        speaking = !!active;
        if (was && !speaking && mouthEnvelope > 0.18) {
            // Audio stopped while still mid-word — reads as a cut-off (barge-in/cancel)
            // rather than a natural turn end. A quick surprised flash, then settle back to
            // whatever the current state's expression actually is.
            applyEmotion('surprised');
            setTimeout(() => { if (state !== 'error') applyEmotion(state); }, 350);
        }
    },
    setMouthLevel(level) {
        mouthLevel = Math.max(0, Math.min(1, Number(level) || 0));
    },
    wave() { waving = 1.6; },
};

// ─── Render loop ──────────────────────────────────────────────────
const mouthShapes = ['aa', 'ih', 'ou', 'ee', 'oh'];
let mouthPhase = 0;
let mouthEnvelope = 0; // smoothed openness actually rendered — decays on its own once speaking stops

function tick() {
    const dt = clock.getDelta();
    const t  = clock.elapsedTime;

    if (vrm) {
        const em = vrm.expressionManager;
        const head = vrm.humanoid?.getNormalizedBoneNode?.('head');

        // Natural idle / conversational motion
        if (head) {
            gazeTimer -= dt;
            if (gazeTimer <= 0) {
                gazeTimer = 1.2 + Math.random() * 2.5;
                gazeX = (Math.random() - 0.5) * 0.22;
                gazeY = (Math.random() - 0.5) * 0.12;
            }
            let targetPitch = gazeY;
            let targetYaw = gazeX;
            let targetRoll = Math.sin(t * 0.35) * 0.018;
            switch (state) {
                case 'listening':
                    targetPitch += -0.08 + Math.sin(t * 1.3) * 0.018;
                    targetYaw += Math.sin(t * 0.75) * 0.025;
                    break;
                case 'thinking':
                    targetPitch += 0.06;
                    targetYaw += 0.14 + Math.sin(t * 0.7) * 0.035;
                    break;
                case 'speaking':
                    targetPitch += Math.sin(t * 1.2) * 0.025;
                    targetYaw += Math.sin(t * 0.9) * 0.045;
                    targetRoll += Math.sin(t * 1.1) * 0.012;
                    break;
                case 'error':
                    targetRoll = Math.sin(t * 6) * 0.055;
                    break;
            }
            if (nodPhase > 0) {
                nodPhase = Math.max(0, nodPhase - dt);
                targetPitch += Math.sin((1 - nodPhase / NOD_DURATION) * Math.PI) * 0.09;
            }
            if (shakePhase > 0) {
                shakePhase = Math.max(0, shakePhase - dt);
                targetYaw += Math.sin((1 - shakePhase / SHAKE_DURATION) * Math.PI * 3) * 0.06 * (shakePhase / SHAKE_DURATION);
            }
            head.rotation.x += (targetPitch - head.rotation.x) * 0.075;
            head.rotation.y += (targetYaw - head.rotation.y) * 0.075;
            head.rotation.z += (targetRoll - head.rotation.z) * 0.075;
        }

        // Independent eye micro-saccades — subtler and quicker than the head's broader gaze
        // drift above, so the eyes read as alive even when the head itself holds steady.
        eyeSaccadeTimer -= dt;
        if (eyeSaccadeTimer <= 0) {
            eyeSaccadeTimer = 0.6 + Math.random() * 1.4;
            eyeTargetX = (Math.random() - 0.5) * 0.10;
            eyeTargetY = (Math.random() - 0.5) * 0.06;
        }
        eyeX += (eyeTargetX - eyeX) * 0.15;
        eyeY += (eyeTargetY - eyeY) * 0.15;
        const leftEye = vrm.humanoid?.getNormalizedBoneNode?.('leftEye');
        const rightEye = vrm.humanoid?.getNormalizedBoneNode?.('rightEye');
        if (leftEye && restPose.leftEye) {
            leftEye.rotation.x = restPose.leftEye.x + eyeY;
            leftEye.rotation.y = restPose.leftEye.y + eyeX;
        }
        if (rightEye && restPose.rightEye) {
            rightEye.rotation.x = restPose.rightEye.x + eyeY;
            rightEye.rotation.y = restPose.rightEye.y + eyeX;
        }

        // Subtle upper-body idle sway so the torso never looks welded in place — separate
        // from the whole-body breathing scale below, which only handles the vertical lift.
        const torso = torsoBoneName ? vrm.humanoid?.getNormalizedBoneNode?.(torsoBoneName) : null;
        if (torso && restPose[torsoBoneName]) {
            const rest = restPose[torsoBoneName];
            torso.rotation.x = rest.x + Math.sin(t * 0.22 + idlePhaseOffset) * 0.008;
            torso.rotation.z = rest.z + Math.sin(t * 0.31 + idlePhaseOffset * 1.3) * 0.006;
        }

        // Smoothly ease the real expression values toward applyEmotion()'s targets instead of
        // snapping — this is what makes idle→speaking (and any transition back to neutral)
        // read as natural rather than instant. Distinct keys from blink/mouth shapes below,
        // so this never touches lip sync.
        if (em) {
            for (const k in expressionTargets) {
                const cur = em.getValue?.(k) || 0;
                em.setValue(k, cur + (expressionTargets[k] - cur) * Math.min(1, dt * 6));
            }
        }

        // Small "talking with hands" gesture — only while actually vocalizing, so it never
        // becomes a constant robotic sway. Left arm always free to use; right arm skipped
        // while a wave is in progress so the two gestures never fight the same bones.
        if (vrm.humanoid) {
            const lUp = vrm.humanoid.getNormalizedBoneNode('leftUpperArm');
            const rUp = vrm.humanoid.getNormalizedBoneNode('rightUpperArm');
            const gestureTarget = (state === 'speaking' && mouthEnvelope > 0.05)
                ? Math.sin(t * 2.1 + idlePhaseOffset) * 0.05 : 0;
            if (lUp) lUp.rotation.x += (gestureTarget - lUp.rotation.x) * 0.08;
            if (rUp && waving <= 0) rUp.rotation.x += (gestureTarget * 0.8 - rUp.rotation.x) * 0.08;
        }

        // Human-ish blinking with variable timing and a quick close/open cycle.
        if (em) {
            if (blinkPhase >= 0) blinkPhase += dt;
            if (blinkPhase < 0 && t >= nextBlinkAt) blinkPhase = 0;
            if (t >= nextBlinkAt && blinkPhase === 0) {
                blinkPhase = 0.0001;
                nextBlinkAt = t + 2.0 + Math.random() * 4.0;
            }
            if (blinkPhase > 0) {
                const p = Math.min(1, blinkPhase / blinkDuration);
                blinkStrength = Math.sin(p * Math.PI);
                if (p >= 1) blinkPhase = -1;
            } else blinkStrength = 0;
            em.setValue('blink', blinkStrength);
        }

        // Audio-driven lip sync: native Live PCM RMS (the actual audio being played, set via
        // setMouthLevel from the real AudioTrack buffer) controls the jaw/mouth. mouthEnvelope
        // is what's actually rendered: it tracks that signal while speaking, and decays
        // smoothly to rest on its own the instant speaking stops or playback is interrupted —
        // so a stale chunk from an old, already-ended turn can't leak into the mouth, and the
        // mouth never snaps shut in a single frame.
        if (em) {
            if (speaking) {
                mouthPhase += dt * (5 + mouthLevel * 16);
                mouthEnvelope = mouthLevel;
            } else {
                mouthPhase = 0;
                mouthLevel = 0;
                mouthEnvelope *= Math.exp(-dt * 14);
            }
            mouthShapes.forEach((m) => em.setValue(m, 0));
            if (mouthEnvelope > 0.01) {
                const shape = mouthShapes[Math.floor(mouthPhase) % mouthShapes.length];
                const next = mouthShapes[(Math.floor(mouthPhase) + 1) % mouthShapes.length];
                const a = Math.min(1, mouthEnvelope * 1.7 + (speaking ? 0.10 : 0));
                const mix = mouthPhase % 1;
                em.setValue(shape, a * (1 - mix));
                em.setValue(next, a * mix * 0.65);
                em.setValue('aa', Math.max(em.getValue?.('aa') || 0, a * 0.35));
            } else {
                mouthEnvelope = 0;
            }
        }

        // Breathing subtly scales the upper body so the character never feels frozen.
        const breathing = 1 + Math.sin(t * 1.7) * 0.0045 + (speaking ? Math.sin(t * 4.2) * 0.002 : 0);
        if (vrm.scene) {
            vrm.scene.scale.y = breathing;
        }

        // VRM internal update (springs, lookAt, etc.)
        if (em) em.update();
        vrm.update(dt);
    }

    // Wave (one-shot) — rotate right upper arm
    if (waving > 0 && vrm?.humanoid) {
        waving -= dt;
        const phase = (1.6 - waving) * 6;
        const upper = vrm.humanoid.getNormalizedBoneNode('rightUpperArm');
        const lower = vrm.humanoid.getNormalizedBoneNode('rightLowerArm');
        if (upper) upper.rotation.z = -1.5 + Math.sin(phase) * 0.5;
        if (lower) lower.rotation.z = -0.5 + Math.sin(phase * 1.6) * 0.3;
        if (waving <= 0 && vrm) relaxArms(vrm);
    }

    renderer.render(scene, camera);
    requestAnimationFrame(tick);
}
tick();

// ─── Resize handling ──────────────────────────────────────────────
function resize() {
    const w = stage.clientWidth, h = stage.clientHeight;
    camera.aspect = w / h;
    camera.updateProjectionMatrix();
    renderer.setSize(w, h, false);
}
new ResizeObserver(resize).observe(stage);
resize();

// Default emotion (also re-applied once VRM finishes loading)
ElinaAI.setEmotion('idle');
