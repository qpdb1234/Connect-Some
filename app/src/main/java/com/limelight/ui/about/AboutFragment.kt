/*
 * Copyright (c) 2021  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.limelight.ui.about

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.limelight.BuildConfig
import com.limelight.R
import com.limelight.databinding.FragmentAboutBinding

class AboutFragment : Fragment() {

    /** The sections on screen, in page order, each remembering whether it is open. */
    private val sections = mutableListOf<Section>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val binding = FragmentAboutBinding.inflate(inflater, container, false)

        binding.version.text = BuildConfig.VERSION_NAME
        addSections(inflater, binding)
        binding.aboutExpandAll.setOnClickListener { setAllExpanded(true) }
        binding.aboutCollapseAll.setOnClickListener { setAllExpanded(false) }

        return binding.root
    }

    /**
     * Builds the body of the page out of the two parallel arrays of section titles and bodies.
     * Driving it from resources rather than from a list of TextViews in the layout keeps a new
     * section a pair of strings, and keeps every section of the page translatable on its own -
     * a language that has not covered one still shows the English text of that one section
     * rather than losing the whole list.
     */
    private fun addSections(inflater: LayoutInflater, binding: FragmentAboutBinding) {
        val res = requireContext().resources
        val titles = res.getStringArray(R.array.about_section_titles)
        val bodies = res.getStringArray(R.array.about_section_bodies)
        val arrowOpen = getString(R.string.about_arrow_expanded)
        val arrowShut = getString(R.string.about_arrow_collapsed)

        sections.clear()
        titles.forEachIndexed { index, title ->
            if (index >= bodies.size) return@forEachIndexed

            val section = inflater.inflate(R.layout.about_section, binding.aboutSections, false)
            val heading: TextView = section.findViewById(R.id.about_section_title)
            val body: TextView = section.findViewById(R.id.about_section_body)
            body.text = bodies[index]

            sections.add(Section(heading, body, title, arrowOpen, arrowShut))
            binding.aboutSections.addView(section)
        }
    }

    private fun setAllExpanded(expanded: Boolean) {
        sections.forEach { it.expanded = expanded }
    }

    /**
     * One heading and the block of bullets behind it. The page starts with every section shut
     * because the full text runs to several screens: someone looking for one aspect of the app
     * should not have to scroll past all the others to get there, while the headings on their own
     * still say what the app covers. A heading is the switch, being the only part of a shut
     * section - see the note in about_section.xml.
     */
    private class Section(
            private val heading: TextView,
            private val body: TextView,
            private val title: CharSequence,
            private val arrowOpen: String,
            private val arrowShut: String
    ) {

        var expanded = false
            set(value) {
                if (field == value) return
                field = value
                render()
            }

        init {
            heading.setOnClickListener { expanded = !expanded }
            render()
        }

        private fun render() {
            heading.text = buildString {
                append(if (expanded) arrowOpen else arrowShut)
                append(' ')
                append(title)
            }
            body.isVisible = expanded
        }
    }

    override fun onResume() {
        super.onResume()
        requireActivity().setTitle(R.string.title_about)
    }
}
